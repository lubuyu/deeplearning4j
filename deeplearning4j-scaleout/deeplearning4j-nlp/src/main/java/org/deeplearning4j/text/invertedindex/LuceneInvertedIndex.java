package org.deeplearning4j.text.invertedindex;

import com.google.common.base.Function;
import org.apache.commons.io.FileUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.Version;
import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.models.word2vec.VocabWord;
import org.deeplearning4j.models.word2vec.wordstore.VocabCache;
import org.deeplearning4j.text.stopwords.StopWords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lucene based inverted index
 *
 * @author Adam Gibson
 */
public class LuceneInvertedIndex implements InvertedIndex,IndexReader.ReaderClosedListener,Iterator<List<VocabWord>> {

    private transient  Directory dir;
    private transient IndexReader reader;
    private   transient Analyzer analyzer;
    private VocabCache vocabCache;
    public final static String WORD_FIELD = "word";
    public final static String LABEL = "label";

    private int numDocs = 0;
    private List<List<VocabWord>> words = new ArrayList<>();
    private boolean cache = true;
    private AtomicBoolean indexBeingCreated = new AtomicBoolean(false);
    private static Logger log = LoggerFactory.getLogger(LuceneInvertedIndex.class);
    public final static String INDEX_PATH = "word2vec-index";
    private AtomicBoolean readerClosed = new AtomicBoolean(false);
    private AtomicInteger totalWords = new AtomicInteger(0);
    private int batchSize = 1000;
    private List<List<VocabWord>> miniBatches = new CopyOnWriteArrayList<>();
    private List<VocabWord> currMiniBatch = Collections.synchronizedList(new ArrayList<VocabWord>());
    private double sample = 0;
    private AtomicLong nextRandom = new AtomicLong(5);
    private String indexPath = INDEX_PATH;
    private Queue<List<VocabWord>> miniBatchDocs = new ConcurrentLinkedDeque<>();
    private AtomicBoolean miniBatchGoing = new AtomicBoolean(true);
    private boolean miniBatch = false;
    public final static String DEFAULT_INDEX_DIR = "word2vec-index";
    private transient SearcherManager searcherManager;
    private transient ReaderManager  readerManager;
    private transient TrackingIndexWriter indexWriter;
    private transient NativeFSLockFactory lockFactory;

    public LuceneInvertedIndex(VocabCache vocabCache,boolean cache) {
        this(vocabCache,cache,DEFAULT_INDEX_DIR);
    }



    public LuceneInvertedIndex(VocabCache vocabCache,boolean cache,String indexPath) {
        this.vocabCache = vocabCache;
        this.cache = cache;
        this.indexPath = indexPath;
        initReader();
    }


    private LuceneInvertedIndex(){
        this(null,false,DEFAULT_INDEX_DIR);
    }

    public LuceneInvertedIndex(VocabCache vocabCache) {
        this(vocabCache,false,DEFAULT_INDEX_DIR);
    }

    @Override
    public Iterator<List<List<VocabWord>>> batchIter(int batchSize) {
        return new BatchDocIter(batchSize);
    }

    @Override
    public Iterator<List<VocabWord>> docs() {
        return new DocIter();
    }

    @Override
    public void unlock() {
        try {
            if(lockFactory == null)
                lockFactory = new NativeFSLockFactory(new File(indexPath));
            IndexWriter.unlock(dir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void cleanup() {
        try {
            indexWriter.deleteAll();
            indexWriter.getIndexWriter().commit();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public double sample() {
        return sample;
    }

    @Override
    public Iterator<List<VocabWord>> miniBatches() {
        return this;
    }

    @Override
    public synchronized List<VocabWord> document(int index) {
        List<VocabWord> ret = new CopyOnWriteArrayList<>();
        try {
            IndexReader reader = getReader();
            Document doc = reader.document(index);
            reader.close();
            String[] values = doc.getValues(WORD_FIELD);
            for(String s : values) {
                ret.add(vocabCache.wordFor(s));
            }



        }


        catch (Exception e) {
            e.printStackTrace();
        }
        return ret;
    }



    @Override
    public int[] documents(VocabWord vocabWord) {
        try {
            TermQuery query = new TermQuery(new Term(WORD_FIELD,vocabWord.getWord().toLowerCase()));
            searcherManager.maybeRefresh();
            IndexSearcher searcher = searcherManager.acquire();
            TopDocs topdocs = searcher.search(query,Integer.MAX_VALUE);
            int[] ret = new int[topdocs.totalHits];
            for(int i = 0; i < topdocs.totalHits; i++) {
                ret[i] = topdocs.scoreDocs[i].doc;
            }


            searcherManager.release(searcher);

            return ret;
        }
        catch(AlreadyClosedException e) {
            return documents(vocabWord);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public int numDocuments() {
        try {
            initReader();
            readerManager.maybeRefresh();
            reader = readerManager.acquire();
            int ret = reader.numDocs();
            reader.close();
            return ret;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public int[] allDocs() {
        if(cache){
            int[] ret = new int[words.size()];
            for(int i = 0; i < words.size(); i++)
                ret[i] = i;
            return ret;
        }


        IndexReader reader = getReader();
        int[] docIds = new int[reader.maxDoc()];
        int count = 0;
        Bits liveDocs = MultiFields.getLiveDocs(reader);

        for(int i = 0; i < reader.maxDoc(); i++) {
            if (liveDocs != null && !liveDocs.get(i))
                continue;

            if(count > docIds.length) {
                int[] newCopy = new int[docIds.length * 2];
                System.arraycopy(docIds,0,newCopy,0,docIds.length);
                docIds = newCopy;
                log.info("Reallocating doc ids");
            }

            docIds[count++] = i;
        }

        try {
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return docIds;
    }

    @Override
    public void addWordToDoc(int doc, VocabWord word) {
        Field f = new TextField(WORD_FIELD,word.getWord(),Field.Store.YES);
        try {
            initReader();
            IndexSearcher searcher = searcherManager.acquire();
            Document doc2 = searcher.doc(doc);
            if(doc2 != null)
                doc2.add(f);
            else {
                Document d = new Document();
                d.add(f);
            }

            searcherManager.release(searcher);


        } catch (IOException e) {
            e.printStackTrace();
        }


    }


    private  void initReader() {
        if(reader == null) {
            try {
                ensureDirExists();
                if(getWriter() == null) {
                    this.indexWriter = null;
                    while(getWriter() == null) {
                        log.warn("Writer was null...reinitializing");
                        Thread.sleep(1000);
                    }
                }
                IndexWriter writer = getWriter().getIndexWriter();
                if(writer == null)
                    throw new IllegalStateException("index writer was null");
                searcherManager = new SearcherManager(writer,true,new SearcherFactory());
                writer.commit();
                readerManager = new ReaderManager(dir);
                DirectoryReader reader = readerManager.acquire();
                numDocs = readerManager.acquire().numDocs();
                readerManager.release(reader);
            }catch(Exception e) {
                throw new RuntimeException(e);
            }

        }
    }

    @Override
    public void addWordsToDoc(int doc,final List<VocabWord> words) {


        Document d = new Document();

        for (VocabWord word : words)
            d.add(new TextField(WORD_FIELD, word.getWord(), Field.Store.YES));



        totalWords.set(totalWords.get() + words.size());
        addWords(words);
        try {
            getWriter().addDocument(d);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    @Override
    public Pair<List<VocabWord>, String> documentWithLabel(int index) {
        List<VocabWord> ret = new CopyOnWriteArrayList<>();
        String label = "NONE";
        try {
            IndexReader reader = getReader();
            Document doc = reader.document(index);
            reader.close();
            String[] values = doc.getValues(WORD_FIELD);
            label = doc.get(LABEL);
            if(label == null)
                label = "NONE";
            for(String s : values) {
                ret.add(vocabCache.wordFor(s));
            }



        }


        catch (Exception e) {
            e.printStackTrace();
        }
        return new Pair<>(ret,label);


    }

    @Override
    public void addLabelForDoc(int doc, VocabWord word) {
        addLabelForDoc(doc,word.getWord());
    }

    @Override
    public void addLabelForDoc(int doc, String label) {
        IndexReader reader = getReader();
        try {
            Document doc2 = reader.document(doc);
            doc2.add(new TextField(LABEL,label,Field.Store.YES));
            reader.close();
            TrackingIndexWriter writer = getWriter();
            Term term = new Term(LABEL,label);
            writer.updateDocument(term,doc2);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void addWordsToDoc(int doc, List<VocabWord> words, String label) {
        Document d = new Document();

        for (VocabWord word : words)
            d.add(new TextField(WORD_FIELD, word.getWord(), Field.Store.YES));

        d.add(new TextField(LABEL,label,Field.Store.YES));

        totalWords.set(totalWords.get() + words.size());
        addWords(words);
        try {
            getWriter().addDocument(d);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void addWordsToDoc(int doc, List<VocabWord> words, VocabWord label) {
        addWordsToDoc(doc,words,label.getWord());
    }


    private void addWords(List<VocabWord> words) {
        if(!miniBatch)
            return;
        for (VocabWord word : words) {
            // The subsampling randomly discards frequent words while keeping the ranking same
            if (sample > 0) {
                double ran = (Math.sqrt(word.getWordFrequency() / (sample * numDocuments())) + 1)
                        * (sample * numDocuments()) / word.getWordFrequency();

                if (ran < (nextRandom.get() & 0xFFFF) / (double) 65536) {
                    continue;
                }

                currMiniBatch.add(word);
            } else {
                currMiniBatch.add(word);
                if (currMiniBatch.size() >= batchSize) {
                    miniBatches.add(new ArrayList<>(currMiniBatch));
                    currMiniBatch.clear();
                }
            }

        }
    }




    private void ensureDirExists() throws Exception {
        if(dir == null) {
            dir = FSDirectory.open(new File(indexPath));
            File dir2 = new File(indexPath);
            if (!dir2.exists())
                dir2.mkdir();
        }


    }


    private synchronized IndexReader getReader() {
        if(reader != null)
            try {
                readerManager.maybeRefresh();
                return readerManager.acquire();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        else {
            initReader();
            return reader;
        }
    }


    private synchronized TrackingIndexWriter getWriterWithRetry() {
        if(this.indexWriter != null)
            return this.indexWriter;

        IndexWriterConfig iwc;
        IndexWriter writer = null;
        try {
            if(analyzer == null)
                analyzer  = new StandardAnalyzer(new InputStreamReader(new ByteArrayInputStream("".getBytes())));


            ensureDirExists();

            if(IndexWriter.isLocked(dir)) {
                IndexWriter.unlock(dir);
            }

            if(this.indexWriter == null) {
                indexBeingCreated.set(true);
                if(IndexWriter.isLocked(dir)) {
                    IndexWriter.unlock(dir);
                }
                if(new File(indexPath).exists()) {
                    FileUtils.deleteDirectory(new File(indexPath));
                }

                iwc = new IndexWriterConfig(Version.LATEST, analyzer);
                iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
                iwc.setWriteLockTimeout(1000);

                dir = FSDirectory.open(new File(indexPath));
                log.info("Creating new index writer");
                while((writer = tryCreateWriter(iwc)) == null) {
                    log.warn("Failed to create writer...trying again");
                    iwc = new IndexWriterConfig(Version.LATEST, analyzer);

                    iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
                    iwc.setWriteLockTimeout(1000);
                    if(dir != null) {

                        dir.clearLock(IndexWriter.WRITE_LOCK_NAME);
                        dir.close();
                    }
                    dir = FSDirectory.open(new File(indexPath),lockFactory);
                    lockFactory.clearLock(IndexWriter.WRITE_LOCK_NAME);
                    dir.clearLock(IndexWriter.WRITE_LOCK_NAME);

                    Thread.sleep(10000);
                }
                this.indexWriter = new TrackingIndexWriter(writer);

            }

        }

        catch(Exception e) {
            throw new IllegalStateException(e);
        }




        return this.indexWriter;
    }


    private IndexWriter tryCreateWriter(IndexWriterConfig iwc) {
        try {
            dir.clearLock(IndexWriter.WRITE_LOCK_NAME);
            if(lockFactory == null)
                lockFactory = new NativeFSLockFactory(new File(indexPath));
            lockFactory.clearLock(IndexWriter.WRITE_LOCK_NAME);
            return new IndexWriter(dir,iwc);
        } catch (IOException e) {
            log.warn("Couldn't create index ",e);
            return null;
        }

    }

    private synchronized  TrackingIndexWriter getWriter() {
        int attempts = 0;
        while(getWriterWithRetry() == null && attempts < 3) {

            try {
                Thread.sleep(1000 * attempts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if(attempts >= 3)
                throw new IllegalStateException("Can't obtain write lock");
            attempts++;

        }

        return this.indexWriter;
    }



    @Override
    public void finish() {
        try {
            initReader();
            IndexReader reader = readerManager.acquire();
            indexWriter.getIndexWriter().commit();
            numDocs = reader.numDocs();
            reader.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public int totalWords() {
        return totalWords.get();
    }

    @Override
    public int batchSize() {
        return batchSize;
    }

    @Override
    public void eachDocWithLabel(final Function<Pair<List<VocabWord>, String>, Void> func, ExecutorService exec) {
        int[] docIds = allDocs();
        for(int i : docIds) {
            final int j = i;
            exec.execute(new Runnable() {
                @Override
                public void run() {
                    func.apply(documentWithLabel(j));
                }
            });
        }
    }

    @Override
    public void eachDoc(final Function<List<VocabWord>, Void> func,ExecutorService exec) {
        int[] docIds = allDocs();
        for(int i : docIds) {
            final int j = i;
            exec.execute(new Runnable() {
                @Override
                public void run() {
                    func.apply(document(j));
                }
            });
        }
    }







    @Override
    public void onClose(IndexReader reader) {
        readerClosed.set(true);
    }

    @Override
    public boolean hasNext() {
        if(!miniBatch)
            throw new IllegalStateException("Mini batch mode turned off");
        return !miniBatchDocs.isEmpty() ||
                miniBatchGoing.get();
    }

    @Override
    public List<VocabWord> next() {
        if(!miniBatch)
            throw new IllegalStateException("Mini batch mode turned off");
        if(!miniBatches.isEmpty())
            return miniBatches.remove(0);
        else if(miniBatchGoing.get()) {
            while(miniBatches.isEmpty()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                log.warn("Waiting on more data...");

                if(!miniBatches.isEmpty())
                    return miniBatches.remove(0);
            }
        }
        return null;
    }




    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }



    public class BatchDocIter implements Iterator<List<List<VocabWord>>> {

        private int batchSize = 1000;
        private int curr = 0;
        private Iterator<List<VocabWord>> docIter = new DocIter();

        public BatchDocIter(int batchSize) {
            this.batchSize = batchSize;
        }

        @Override
        public boolean hasNext() {
            return docIter.hasNext();
        }

        @Override
        public List<List<VocabWord>> next() {
            List<List<VocabWord>> ret = new ArrayList<>();
            for(int i = 0; i < batchSize; i++) {
                if(!docIter.hasNext())
                    break;
                ret.add(docIter.next());
            }
            return ret;
        }
    }


    public class DocIter implements Iterator<List<VocabWord>> {
        private int currIndex = 0;
        private int[] docs = allDocs();


        @Override
        public boolean hasNext() {
            return currIndex < docs.length;
        }

        @Override
        public List<VocabWord> next() {
            return document(docs[currIndex++]);
        }
    }


    public static class Builder {
        private File indexDir;
        private  Directory dir;
        private IndexReader reader;
        private   Analyzer analyzer;
        private IndexSearcher searcher;
        private IndexWriter writer;
        private  IndexWriterConfig iwc = new IndexWriterConfig(Version.LUCENE_CURRENT, analyzer);
        private VocabCache vocabCache;
        private List<String> stopWords = StopWords.getStopWords();
        private boolean cache = true;
        private int batchSize = 1000;
        private double sample = 0;
        private boolean miniBatch = false;



        public Builder miniBatch(boolean miniBatch) {
            this.miniBatch = miniBatch;
            return this;
        }
        public Builder cacheInRam(boolean cache) {
            this.cache = cache;
            return this;
        }

        public Builder sample(double sample) {
            this.sample = sample;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }



        public Builder indexDir(File indexDir) {
            this.indexDir = indexDir;
            return this;
        }

        public Builder cache(VocabCache cache) {
            this.vocabCache = cache;
            return this;
        }

        public Builder stopWords(List<String> stopWords) {
            this.stopWords = stopWords;
            return this;
        }

        public Builder dir(Directory dir) {
            this.dir = dir;
            return this;
        }

        public Builder reader(IndexReader reader) {
            this.reader = reader;
            return this;
        }

        public Builder writer(IndexWriter writer) {
            this.writer = writer;
            return this;
        }

        public Builder analyzer(Analyzer analyzer) {
            this.analyzer = analyzer;
            return this;
        }

        public InvertedIndex build() {
            LuceneInvertedIndex ret;
            if(indexDir != null) {
                ret = new LuceneInvertedIndex(vocabCache,cache,indexDir.getAbsolutePath());
            }
            else
                ret = new LuceneInvertedIndex(vocabCache);
            try {
                ret.batchSize = batchSize;

                if(dir != null)
                    ret.dir = dir;
                ret.miniBatch = miniBatch;
                if(reader != null)
                    ret.reader = reader;
                if(analyzer != null)
                    ret.analyzer = analyzer;
            }catch(Exception e) {
                throw new RuntimeException(e);
            }

            return ret;

        }

    }


}

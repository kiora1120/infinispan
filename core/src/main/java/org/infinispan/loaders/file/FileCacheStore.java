package org.infinispan.loaders.file;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.infinispan.Cache;
import org.infinispan.commons.equivalence.AnyEquivalence;
import org.infinispan.commons.equivalence.Equivalence;
import org.infinispan.commons.equivalence.EquivalentLinkedHashMap;
import org.infinispan.commons.util.CollectionFactory;
import org.infinispan.commons.util.Util;
import org.infinispan.configuration.cache.CacheLoaderConfiguration;
import org.infinispan.configuration.cache.FileCacheStoreConfiguration;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.InternalCacheValue;
import org.infinispan.loaders.CacheLoaderException;
import org.infinispan.loaders.spi.AbstractCacheStore;
import org.infinispan.loaders.spi.CacheStore;
import org.infinispan.commons.marshall.StreamingMarshaller;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * A filesystem-based implementation of a {@link CacheStore}. This file store
 * stores cache values in a single file <tt>&lt;location&gt;/&lt;cache name&gt;.dat</tt>,
 * keys and file positions are kept in memory.
 * <p/>
 * Note: this CacheStore implementation keeps keys and file positions in memory!
 * The current implementation needs about 100 bytes per cache entry, plus the
 * memory for the key objects.
 * <p/>
 * So, the space taken by this cache store is both the space in the file
 * itself plus the in-memory index with the keys and their file positions.
 * With this in mind and to avoid the cache store leading to
 * OutOfMemoryExceptions, you can optionally configure the maximum number
 * of entries to maintain in this cache store, which affects both the size
 * of the file and the size of the in-memory index. However, setting this
 * maximum limit results in older entries in the cache store to be eliminated,
 * and hence, it only makes sense configuring a maximum limit if Infinispan
 * is used as a cache where loss of data in the cache store does not lead to
 * data loss, and data can be recomputed or re-queried from the original data
 * source.
 * <p/>
 * This class is fully thread safe, yet allows for concurrent load / store
 * of individual cache entries.
 *
 * @author Karsten Blees
 * @since 6.0
 */
public class FileCacheStore extends AbstractCacheStore {

   private static final Log log = LogFactory.getLog(FileCacheStore.class);

   private static final byte[] MAGIC = new byte[] { 'F', 'C', 'S', '1' };
   private static final byte[] ZERO_INT = { 0, 0, 0, 0 };
   private static final int KEYLEN_POS = 4;
   private static final int KEY_POS = 4 + 4 + 4 + 8;

   private FileCacheStoreConfiguration configuration;

   private FileChannel file;
   private Map<Object, FileEntry> entries;
   private SortedSet<FileEntry> freeList;
   private long filePos = MAGIC.length;


   /** {@inheritDoc} */
   @Override
   public void init(CacheLoaderConfiguration configuration, Cache<?, ?> cache, StreamingMarshaller m) throws
           CacheLoaderException {
      this.configuration = validateConfigurationClass(configuration, FileCacheStoreConfiguration.class);
      super.init(configuration, cache, m);
   }

   /** {@inheritDoc} */
   @Override
   public void start() throws CacheLoaderException {
      super.start();
      try {
         // open the data file
         String location = configuration.location();
         if (location == null || location.trim().length() == 0)
            location = "Infinispan-SingleFileCacheStore";

         File f = new File(location + File.separator + cache.getName() + ".dat");
         if (!f.exists()) {
             File dir = f.getParentFile();
             if (!dir.exists() && !dir.mkdirs()) {
                 throw log.directoryCannotBeCreated(dir.getAbsolutePath());
             }
         }
         file = new RandomAccessFile(f, "rw").getChannel();

         // initialize data structures
         entries = newEntryMap();
         freeList = Collections.synchronizedSortedSet(new TreeSet<FileEntry>());

         // Upgrade old file cache store structure, if present
         upgradeFileCacheStoreIfNeeded(location, cache.getName());

         // check file format and read persistent state if enabled for the cache
         byte[] header = new byte[MAGIC.length];
         if (file.read(ByteBuffer.wrap(header), 0) == MAGIC.length && Arrays.equals(MAGIC, header))
            rebuildIndex();
         else
            clear(); // otherwise (unknown file format or no preload) just reset the file
      } catch (Exception e) {
         throw new CacheLoaderException(e);
      }
   }

   private Map<Object, FileEntry> newEntryMap() {
      // only use LinkedHashMap (LRU) for entries when cache store is bounded
      final Map<Object, FileEntry> entryMap;
      Equivalence<Object> keyEq = cache.getCacheConfiguration().dataContainer().keyEquivalence();
      if (configuration.maxEntries() > 0)
         entryMap = CollectionFactory.makeLinkedMap(16, 0.75f,
               EquivalentLinkedHashMap.IterationOrder.ACCESS_ORDER,
               keyEq, AnyEquivalence.<FileEntry>getInstance());
      else
         entryMap = CollectionFactory.makeMap(keyEq, AnyEquivalence.<FileEntry>getInstance());

      return Collections.synchronizedMap(entryMap);
   }

   private void upgradeFileCacheStoreIfNeeded(String cfgLocation, String cacheName) throws CacheLoaderException {
      // If old file cache store detected, back it up
      File backupRoot;
      try {
         backupRoot = BucketFileCacheStore.backUpStore(cfgLocation, cacheName);
      } catch (CacheLoaderException e) {
         throw log.unableToUpgradeBucketBasedFileCacheStore(e);
      }

      if (backupRoot != null) {
         // Clear store and re-create folder
         File root = BucketFileCacheStore.getLocation(cfgLocation, cacheName);
         Util.recursiveFileRemove(root);
         if (!root.mkdirs())
            log.problemsCreatingDirectory(root);

         // Reset single file so that it contains the right magic header
         clear();

         // Load all entries from old backed-up store and store them again
         // (Not very efficient with the current API available, but new API should make improve this)
         BucketFileCacheStore fcs = new BucketFileCacheStore();
         fcs.setRoot(backupRoot);
         fcs.setMarshaller(marshaller);
         fcs.setFileSync(new BucketFileCacheStore.PerWriteFileSync());
         Set<InternalCacheEntry> all = fcs.loadAllLockSafe();
         for (InternalCacheEntry entry : all)
            store(entry);
      }

   }

   /** {@inheritDoc} */
   @Override
   public void stop() throws CacheLoaderException {
      try {
         if (file != null) {
            // reset state
            file.close();
            file = null;
            entries = null;
            freeList = null;
            filePos = MAGIC.length;
         }
      } catch (Exception e) {
         throw new CacheLoaderException(e);
      }
      super.stop();
   }

   /**
    * Rebuilds the in-memory index from file.
    */
   private void rebuildIndex() throws Exception {
      ByteBuffer buf = ByteBuffer.allocate(KEY_POS);
      for (;;) {
         // read FileEntry fields from file (size, keyLen etc.)
         buf.clear().limit(KEY_POS);
         file.read(buf, filePos);
         // return if end of file is reached
         if (buf.remaining() > 0)
            return;
         buf.flip();

         // initialize FileEntry from buffer
         FileEntry fe = new FileEntry(filePos, buf.getInt());
         fe.keyLen = buf.getInt();
         fe.dataLen = buf.getInt();
         fe.expiryTime = buf.getLong();

         // update file pointer
         filePos += fe.size;

         // check if the entry is used or free
         if (fe.keyLen > 0) {
            // load the key from file
            if (buf.capacity() < fe.keyLen)
               buf = ByteBuffer.allocate(fe.keyLen);

            buf.clear().limit(fe.keyLen);
            file.read(buf, fe.offset + KEY_POS);

            // deserialize key and add to entries map
            Object key = getMarshaller().objectFromByteBuffer(buf.array(), 0, fe.keyLen);
            entries.put(key, fe);
         } else {
            // add to free list
            freeList.add(fe);
         }
      }
   }

   /**
    * {@inheritDoc}
    * <p/>
    * The base class implementation calls {@link #load(Object)} for this, we can do better because
    * we keep all keys in memory.
    */
   @Override
   public boolean containsKey(Object key) throws CacheLoaderException {
      return entries.containsKey(key);
   }

   /**
    * Allocates the requested space in the file.
    *
    * @param len requested space
    * @return allocated file position and length as FileEntry object
    */
   private FileEntry allocate(int len) {
      synchronized (freeList) {
         // lookup a free entry of sufficient size
         SortedSet<FileEntry> candidates = freeList.tailSet(new FileEntry(0, len));
         for (Iterator<FileEntry> it = candidates.iterator(); it.hasNext();) {
            FileEntry free = it.next();
            // ignore entries that are still in use by concurrent readers
            if (free.isLocked())
               continue;

            // There's no race condition risk between locking the entry on
            // loading and checking whether it's locked (or store allocation),
            // because for the entry to be lockable, it needs to be in the
            // entries collection, in which case it's not in the free list.
            // The only way an entry can be found in the free list is if it's
            // been removed, and to remove it, lock on "entries" needs to be
            // acquired, which is also a pre-requisite for loading data.

            // found one, remove from freeList
            it.remove();
            return free;
         }

         // no appropriate free section available, append at end of file
         FileEntry fe = new FileEntry(filePos, len);
         filePos += len;
         return fe;
      }
   }

   /**
    * Frees the space of the specified file entry (for reuse by allocate).
    *
    * @param fe
    *           FileEntry to free
    */
   private void free(FileEntry fe) throws IOException {
      if (fe != null) {
         // invalidate entry on disk (by setting keyLen field to 0)
         file.write(ByteBuffer.wrap(ZERO_INT), fe.offset + KEYLEN_POS);
         freeList.add(fe);
      }
   }

   /** {@inheritDoc} */
   @Override
   public void store(InternalCacheEntry entry) throws CacheLoaderException {
      try {
         // serialize cache value
         byte[] key = getMarshaller().objectToByteBuffer(entry.getKey());
         byte[] data = getMarshaller().objectToByteBuffer(entry.toInternalCacheValue());

         // allocate file entry and store in cache file
         int len = KEY_POS + key.length + data.length;
         FileEntry fe = allocate(len);
         try {
            fe.expiryTime = entry.getExpiryTime();
            fe.keyLen = key.length;
            fe.dataLen = data.length;

            ByteBuffer buf = ByteBuffer.allocate(len);
            buf.putInt(fe.size);
            buf.putInt(fe.keyLen);
            buf.putInt(fe.dataLen);
            buf.putLong(fe.expiryTime);
            buf.put(key);
            buf.put(data);
            buf.flip();
            file.write(buf, fe.offset);

            // add the new entry to in-memory index
            fe = entries.put(entry.getKey(), fe);

            // if we added an entry, check if we need to evict something
            if (fe == null)
               fe = evict();
         } finally {
            // in case we replaced or evicted an entry, add to freeList
            free(fe);
         }
      } catch (Exception e) {
         throw new CacheLoaderException(e);
      }
   }

   /**
    * Try to evict an entry if the capacity of the cache store is reached.
    *
    * @return FileEntry to evict, or null (if unbounded or capacity is not yet reached)
    */
   private FileEntry evict() {
      if (configuration.maxEntries() > 0) {
         synchronized (entries) {
            if (entries.size() > configuration.maxEntries()) {
               Iterator<FileEntry> it = entries.values().iterator();
               FileEntry fe = it.next();
               it.remove();
               return fe;
            }
         }
      }
      return null;
   }

   /** {@inheritDoc} */
   @Override
   public void clear() throws CacheLoaderException {
      try {
         synchronized (entries) {
            synchronized (freeList) {
               // wait until all readers are done reading file entries
               for (FileEntry fe : entries.values())
                  fe.waitUnlocked();
               for (FileEntry fe : freeList)
                  fe.waitUnlocked();

               // clear in-memory state
               entries.clear();
               freeList.clear();

               // reset file
               file.truncate(0);
               file.write(ByteBuffer.wrap(MAGIC), 0);
               filePos = MAGIC.length;
            }
         }
      } catch (Exception e) {
         throw new CacheLoaderException(e);
      }
   }

   /** {@inheritDoc} */
   @Override
   public boolean remove(Object key) throws CacheLoaderException {
      try {
         FileEntry fe = entries.remove(key);
         free(fe);
         return fe != null;
      } catch (Exception e) {
         throw new CacheLoaderException(e);
      }
   }

   /** {@inheritDoc} */
   @Override
   public InternalCacheEntry load(Object key) throws CacheLoaderException {
      try {
         final FileEntry fe;
         final boolean expired;
         synchronized (entries) {
            // lookup FileEntry of the key
            fe = entries.get(key);
            if (fe == null)
               return null;

            // if expired, remove the entry (within entries monitor)
            expired = fe.isExpired(System.currentTimeMillis());
            if (expired)
               entries.remove(key);

            // lock entry for reading before releasing entries monitor
            fe.lock();
         }

         final byte[] data;
         try {
            // if expired, free the file entry (after releasing entries monitor)
            if (expired) {
               free(fe);
               return null;
            }

            // load serialized data from disk
            data = new byte[fe.dataLen];
            file.read(ByteBuffer.wrap(data), fe.offset + KEY_POS + fe.keyLen);
         } finally {
            // no need to keep the lock for deserialization
            fe.unlock();
         }

         // deserialize data and recreate InternalCacheEntry
         return ((InternalCacheValue) getMarshaller().objectFromByteBuffer(data)).toInternalCacheEntry(key);
      } catch (Exception e) {
         throw new CacheLoaderException(e);
      }
   }

   /** {@inheritDoc} */
   @Override
   public Set<InternalCacheEntry> loadAll() throws CacheLoaderException {
      return load(Integer.MAX_VALUE);
   }

   /** {@inheritDoc} */
   @Override
   public Set<InternalCacheEntry> load(int numEntries) throws CacheLoaderException {
      Set<Object> keys = loadAllKeys(null);
      Set<InternalCacheEntry> result = new HashSet<InternalCacheEntry>();
      for (Object key : keys) {
         InternalCacheEntry ice = load(key);
         if (ice != null) {
            result.add(ice);
            if (result.size() >= numEntries)
               return result;
         }
      }
      return result;
   }

   /** {@inheritDoc} */
   @Override
   public Set<Object> loadAllKeys(Set<Object> keysToExclude) throws CacheLoaderException {
      Set<Object> result;
      synchronized (entries) {
         result = new HashSet<Object>(entries.keySet());
      }
      if (keysToExclude != null)
         result.removeAll(keysToExclude);
      return result;
   }

   /** {@inheritDoc} */
   @Override
   protected void purgeInternal() throws CacheLoaderException {
      long now = System.currentTimeMillis();
      synchronized (entries) {
         for (Iterator<FileEntry> it = entries.values().iterator(); it.hasNext();) {
            FileEntry fe = it.next();
            if (fe.isExpired(now)) {
               it.remove();
               try {
                  free(fe);
               } catch (Exception e) {
                  throw new CacheLoaderException(e);
               }
            }
         }
      }
   }

   /** {@inheritDoc} */
   @Override
   public void fromStream(ObjectInput inputStream) throws CacheLoaderException {
      // seems that this is never called by Infinispan (except by decorators)
      throw new UnsupportedOperationException();
   }

   /** {@inheritDoc} */
   @Override
   public void toStream(ObjectOutput outputStream) throws CacheLoaderException {
      // seems that this is never called by Infinispan (except by decorators)
      throw new UnsupportedOperationException();
   }

   Map<Object, FileEntry> getEntries() {
      return entries;
   }

   SortedSet<FileEntry> getFreeList() {
      return freeList;
   }

   /**
    * Helper class to represent an entry in the cache file.
    * <p/>
    * The format of a FileEntry on disk is as follows:
    * <ul>
    * <li>4 bytes: {@link #size}</li>
    * <li>4 bytes: {@link #keyLen}, 0 if the block is unused</li>
    * <li>4 bytes: {@link #dataLen}</li>
    * <li>8 bytes: {@link #expiryTime}</li>
    * <li>{@link #keyLen} bytes: serialized key</li>
    * <li>{@link #dataLen} bytes: serialized data</li>
    * </ul>
    */
   private static class FileEntry implements Comparable<Object> {
      /**
       * File offset of this block.
       */
      private final long offset;

      /**
       * Total size of this block.
       */
      private final int size;

      /**
       * Size of serialized key.
       */
      private int keyLen;

      /**
       * Size of serialized data.
       */
      private int dataLen;

      /**
       * Time stamp when the entry will expire (i.e. will be collected by purge).
       */
      private long expiryTime = -1;

      /**
       * Number of current readers.
       */
      private transient int readers = 0;

      private FileEntry(long offset, int size) {
         this.offset = offset;
         this.size = size;
      }

      private synchronized boolean isLocked() {
         return readers > 0;
      }

      private synchronized void lock() {
         readers++;
      }

      private synchronized void unlock() {
         readers--;
         if (readers == 0)
            notifyAll();
      }

      private synchronized void waitUnlocked() {
         while (readers > 0) {
            try {
               wait();
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
            }
         }
      }

      private boolean isExpired(long now) {
         return expiryTime > 0 && expiryTime < now;
      }

      /** {@inheritDoc} */
      @Override
      public int compareTo(Object o) {
         FileEntry fe = (FileEntry) o;
         if (this == fe)
            return 0;
         int diff = size - fe.size;
         return (diff != 0) ? diff : offset > fe.offset ? 1 : -1;
      }
   }
}

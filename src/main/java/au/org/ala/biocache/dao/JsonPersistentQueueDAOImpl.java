/**************************************************************************
 *  Copyright (C) 2010 Atlas of Living Australia
 *  All Rights Reserved.
 * 
 *  The contents of this file are subject to the Mozilla Public
 *  License Version 1.1 (the "License"); you may not use this file
 *  except in compliance with the License. You may obtain a copy of
 *  the License at http://www.mozilla.org/MPL/
 * 
 *  Software distributed under the License is distributed on an "AS
 *  IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 *  implied. See the License for the specific language governing
 *  rights and limitations under the License.
 ***************************************************************************/
package au.org.ala.biocache.dao;

import au.org.ala.biocache.dto.DownloadDetailsDTO;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A queue that stores the Downloads as JSON files in the supplied directory
 * 
 * @author Natasha Carter (natasha.carter@csiro.au)
 */
@Component("persistentQueueDao")
public class JsonPersistentQueueDAOImpl implements PersistentQueueDAO {
	
    /** log4 j logger */
    private static final Logger logger = Logger.getLogger(JsonPersistentQueueDAOImpl.class);
    private String cacheDirectory="/data/cache/downloads";
    private static final String FILE_PREFIX = "offline";

    @Value("${download.dir:/data/biocache-download}")
    protected String biocacheDownloadDir;

    private final ObjectMapper jsonMapper = new ObjectMapper();
    
    private final Queue<DownloadDetailsDTO> offlineDownloadList = new LinkedBlockingQueue<>();

    private final Object listLock = new Object();

    /**
     * Start closed and wait until the {@link #init()} method completes to accept downloads.<br>
     * Otherwise there is the chance that they will be clobbered or fail to be added correctly by the "forceMkdir" code 
     * or the refresh that clears the queue and refreshes it from the JSON files on disk.<br>
     * Can also be closed by a call to the {@link #shutdown()} method.
     */
    private final AtomicBoolean closed = new AtomicBoolean(true);
    
    /**
     * Ensures initialisation is only attempted once, to avoid clobbering the queue by a reinitialisation.
     */
    private final AtomicBoolean initialised = new AtomicBoolean(false);
    
    @PostConstruct
    public void init() {
        // Ensure the initialisation code is only called once
        if(initialised.compareAndSet(false, true)) {
            synchronized (listLock) {
                jsonMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    
                File file = new File(cacheDirectory);
                try {
                    FileUtils.forceMkdir(file);
                } catch (IOException e) {
                    logger.error("Unable to construct cache directory.", e);
                }
    
                refreshFromPersistent();
                closed.set(false);
            }
        }
    }
    /**
     * Returns a file object that represents the a persisted download on the queue
     * @param key
     * @return
     */
    private File getFile(long key) {
        return new File(cacheDirectory +File.separator+ FILE_PREFIX + key + ".json");
    }
    /**
     * @see au.org.ala.biocache.dao.PersistentQueueDAO#addDownloadToQueue(DownloadDetailsDTO)
     */
    @Override
    public void addDownloadToQueue(DownloadDetailsDTO download) {
        if(!closed.get()) {
            synchronized (listLock) {
                boolean allGood = false;
                try {
                    File f = getFile(download.getStartTime());
                    jsonMapper.writeValue(f, download);
                    allGood  = true;
                } catch (Exception e) {
                    logger.error("Unable to cache the download", e);
                }
                finally {
                    if(allGood) {
                        offlineDownloadList.add(download);
                    }
                }
            }
        }
    }
    /**
     * @see au.org.ala.biocache.dao.PersistentQueueDAO#getNextDownload()
     */
    @Override
    public DownloadDetailsDTO getNextDownload() {
        synchronized (listLock) {
            for (DownloadDetailsDTO dd : offlineDownloadList) {
                if (dd.getFileLocation() == null) {
                    //give a place for the downlaod
                    dd.setFileLocation(biocacheDownloadDir + File.separator + UUID.nameUUIDFromBytes(dd.getEmail().getBytes()) + File.separator + dd.getStartTime() + File.separator + dd.getRequestParams().getFile() + ".zip");
                    return dd;
                }
            }
        }
        
        //if we reached here all of the downloads have started or there are no downloads on the list
        return null;
    }

    /**
     * @see au.org.ala.biocache.dao.PersistentQueueDAO#getNextDownload(Integer maxRecords, au.org.ala.biocache.dto.DownloadDetailsDTO.DownloadType type)
     */
    @Override
    public DownloadDetailsDTO getNextDownload(Integer maxRecords, DownloadDetailsDTO.DownloadType type) {
        synchronized (listLock) {
            for (DownloadDetailsDTO dd : offlineDownloadList) {
                if (dd.getFileLocation() == null &&
                        (maxRecords == null || dd.getTotalRecords() <= maxRecords) &&
                        (type == null || dd.getDownloadType().equals(type))) {
                    //give a place for the downlaod
                    dd.setFileLocation(biocacheDownloadDir + File.separator + UUID.nameUUIDFromBytes(dd.getEmail().getBytes()) + File.separator + dd.getStartTime() + File.separator + dd.getRequestParams().getFile() + ".zip");
                    return dd;
                }
            }
        }

        //if we reached here all of the downloads have started or there are no downloads on the list
        return null;
    }
    
    /**
     * @see au.org.ala.biocache.dao.PersistentQueueDAO#getTotalDownloads()
     */
    @Override
    public int getTotalDownloads() {
        synchronized (listLock) {
            return offlineDownloadList.size();
        }
    }
    
    /**
     * @see au.org.ala.biocache.dao.PersistentQueueDAO#removeDownloadFromQueue(au.org.ala.biocache.dto.DownloadDetailsDTO)
     */
    @Override
    public void removeDownloadFromQueue(DownloadDetailsDTO download) {
        synchronized (listLock) {
            logger.debug("Removing the download from the queue");
            // delete it from the directory
            try {
                File f = getFile(download.getStartTime());
                if (logger.isInfoEnabled()) {
                    logger.info("Deleting " + f.getAbsolutePath() + " " + f.exists());
                }
                FileUtils.deleteQuietly(f);
            }
            finally {
                offlineDownloadList.remove(download);
            }
        }
        
    }
    
    /**
     * @see au.org.ala.biocache.dao.PersistentQueueDAO#getAllDownloads()
     */
    @Override
    public List<DownloadDetailsDTO> getAllDownloads() {
        synchronized (listLock) {
            List<DownloadDetailsDTO> result = new ArrayList<>(offlineDownloadList);
            return Collections.unmodifiableList(result);
        }
    }
    
    /**
     * @see au.org.ala.biocache.dao.PersistentQueueDAO#refreshFromPersistent()
     */
    @Override
    public void refreshFromPersistent() {
        synchronized (listLock) {
            offlineDownloadList.clear();
            File file = new File(cacheDirectory);
            //load the list with the available downloads ordering by the least recently modified
            File[] files = file.listFiles();
            if (files != null) {
                Arrays.sort(files, new Comparator<File>() {
                    @Override
                    public int compare(File o1, File o2) {
                        return (int) (((File) o1).lastModified() - ((File) o2).lastModified());
                    }
                });

                //value = jsonMapper.readValue(file, ParamsCacheObject.class);
                for (File f : files) {
                    if (f.isFile()) {
                        try {
                            DownloadDetailsDTO dd = jsonMapper.readValue(f, DownloadDetailsDTO.class);
                            // Ensure that previously partially downloaded files get their downloads 
                            // reattempted by making them available for download again and removing 
                            // any partial files that already exist for it
                            String previousFileLocation = dd.getFileLocation();
                            dd.setFileLocation(null);
                            if (previousFileLocation != null) {
                                FileUtils.deleteQuietly(new File(previousFileLocation));
                            }
                            offlineDownloadList.add(dd);
                        } catch (Exception e) {
                            logger.error("Unable to load cached download " + f.getAbsolutePath(), e);
                        }
                    }
                }
            }
        }
    }

    /**
     * @see au.org.ala.biocache.dao.PersistentQueueDAO#isInQueue(au.org.ala.biocache.dto.DownloadDetailsDTO dd)
     */
    @Override
    public DownloadDetailsDTO isInQueue(DownloadDetailsDTO dd) {
        synchronized (listLock) {
            for (DownloadDetailsDTO d : offlineDownloadList) {
                if (d.getEmail().equalsIgnoreCase(d.getEmail()) &&
                        d.getDownloadParams().equalsIgnoreCase(dd.getDownloadParams())) {
                    return d;
                }
            }
        }

        //if we reached here it was not found
        return null;
    }
    
    @Override
    public void shutdown() {
        closed.set(true);
    }
}

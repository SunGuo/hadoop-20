/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import java.io.*;
import java.net.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.lang.Math;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.server.namenode.NNStorage.NameNodeDirType;
import org.apache.hadoop.hdfs.server.protocol.RemoteEditLog;
import org.apache.hadoop.hdfs.util.DataTransferThrottler;
import org.apache.hadoop.hdfs.util.InjectionEvent;
import org.apache.hadoop.hdfs.util.InjectionHandler;
import org.apache.hadoop.io.MD5Hash;


/**
 * This class provides fetching a specified file from the NameNode.
 */
class TransferFsImage implements FSConstants{
  
  // Image transfer timeout
  public static final String DFS_IMAGE_TRANSFER_TIMEOUT_KEY = "dfs.image.transfer.timeout";
  public static final int DFS_IMAGE_TRANSFER_TIMEOUT_DEFAULT = 60 * 1000;
  
  public final static String CONTENT_LENGTH = "Content-Length";
  public final static String MD5_HEADER = "X-MD5-Digest";

  private static final Log LOG = LogFactory.getLog(TransferFsImage.class);

  static MD5Hash downloadImageToStorage(
      String fsName, long imageTxId, NNStorage dstStorage, boolean needDigest)
      throws IOException {

    String fileid = GetImageServlet.getParamStringForImage(
        imageTxId, dstStorage);
    
    String fileName = NNStorage.getCheckpointImageFileName(imageTxId);

    File[] dstFiles = dstStorage.getFiles(NameNodeDirType.IMAGE, fileName);
    if (dstFiles.length == 0) {
      throw new IOException("No targets in destination storage!");
    }
    
    MD5Hash hash = getFileClient(fsName, fileid, dstFiles, dstStorage, needDigest);
    LOG.info("Downloaded file " + dstFiles[0].getName() + " size " +
        dstFiles[0].length() + " bytes.");
    return hash;
  }
  
  static void downloadEditsToStorage(String fsName, RemoteEditLog log,
      NNStorage dstStorage) throws IOException {
    assert log.getStartTxId() > 0 && log.getEndTxId() > 0 :
      "bad log: " + log;
    String fileid = GetImageServlet.getParamStringForLog(
        log, dstStorage);
    String fileName = NNStorage.getFinalizedEditsFileName(
        log.getStartTxId(), log.getEndTxId());

    File[] dstFiles = dstStorage.getFiles(NameNodeDirType.EDITS, fileName);
    assert !(dstFiles.length == 0) : "No checkpoint targets.";
    
    for (File f : dstFiles) {
      if (f.exists() && f.canRead()) {
        LOG.info("Skipping download of remote edit log " +
            log + " since it already is stored locally at " + f);
        return;
      } else {
        LOG.debug("Dest file: " + f);
      }
    }

    getFileClient(fsName, fileid, dstFiles, dstStorage, false);
    LOG.info("Downloaded file " + dstFiles[0].getName() + " size " +
        dstFiles[0].length() + " bytes.");
  }
 
  /**
   * Requests that the NameNode download an image from this node.
   *
   * @param fsName the http address for the remote NN
   * @param imageListenAddress the host/port where the local node is running an
   *                           HTTPServer hosting GetImageServlet
   * @param storage the storage directory to transfer the image from
   * @param txid the transaction ID of the image to be uploaded
   */
  static void uploadImageFromStorage(String fsName,
      String machine, int port,
      NNStorage storage, long txid) throws IOException {
    
    String fileid = GetImageServlet.getParamStringToPutImage(
        txid, machine, port, storage);

    LOG.info("Image upload: Posted URL " + fsName + fileid);
    
    // this doesn't directly upload an image, but rather asks the NN
    // to connect back to the 2NN to download the specified image.
    TransferFsImage.getFileClient(fsName, fileid, null, storage, false);
    LOG.info("Uploaded image with txid " + txid + " to namenode at " +
        fsName);
  }

  
  /**
   * A server-side method to respond to a getfile http request
   * Copies the contents of the local file into the output stream.
   */
  static void getFileServer(OutputStream outstream, File localfile,
      DataTransferThrottler throttler) 
    throws IOException {
    byte buf[] = new byte[BUFFER_SIZE];
    FileInputStream infile = null;
    try {
      infile = new FileInputStream(localfile);
      if (InjectionHandler.falseCondition(InjectionEvent.TRANSFERFSIMAGE_GETFILESERVER0)
          && localfile.getAbsolutePath().contains("secondary")) {
        // throw exception only when the secondary sends its image
        throw new IOException("If this exception is not caught by the " +
            "name-node fs image will be truncated.");
      }
      
      if (InjectionHandler.falseCondition(InjectionEvent.TRANSFERFSIMAGE_GETFILESERVER1)
          && localfile.getAbsolutePath().contains("fsimage")) {
          // Test sending image shorter than localfile
          long len = localfile.length();
          buf = new byte[(int)Math.min(len/2, BUFFER_SIZE)];
          // This will read at most half of the image
          // and the rest of the image will be sent over the wire
          infile.read(buf);
      }
      int num = 1;
      while (num > 0) {
        num = infile.read(buf);
        if (num <= 0) {
          break;
        }

        if (InjectionHandler.falseCondition(InjectionEvent.TRANSFERFSIMAGE_GETFILESERVER2)) {
          // Simulate a corrupted byte on the wire
          LOG.warn("SIMULATING A CORRUPT BYTE IN IMAGE TRANSFER!");
          buf[0]++;
        }
        InjectionHandler.processEvent(InjectionEvent.TRANSFERFSIMAGE_GETFILESERVER3);
        outstream.write(buf, 0, num);
        if (throttler != null) {
          throttler.throttle(num);
        }
      }
    } finally {
      if (infile != null) {
        infile.close();
      }
    }
  }
  
  /**
   * Get connection and read timeout.
   */
  private static int getHttpTimeout(NNStorage storage) {
    if (storage == null || storage.getConf() == null) {
      return DFS_IMAGE_TRANSFER_TIMEOUT_DEFAULT;
    }
    return storage.getConf().getInt(DFS_IMAGE_TRANSFER_TIMEOUT_KEY,
        DFS_IMAGE_TRANSFER_TIMEOUT_DEFAULT);
  }

  /**
   * Client-side Method to fetch file from a server
   * Copies the response from the URL to a list of local files.
   * @param dstStorage if an error occurs writing to one of the files,
   *                   this storage object will be notified. 
   * @Return a digest of the received file if getChecksum is true
   */
  static MD5Hash getFileClient(String nnHostPort,
      String queryString, File[] localPaths,
      NNStorage dstStorage, boolean getChecksum) throws IOException {
    byte[] buf = new byte[BUFFER_SIZE];
    String proto = "http://";
    StringBuilder str = new StringBuilder(proto+nnHostPort+"/getimage?");
    str.append(queryString);

    //
    // open connection to remote server
    //
    URL url = new URL(str.toString());
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    
    int timeout = getHttpTimeout(dstStorage);
    connection.setConnectTimeout(timeout);
    connection.setReadTimeout(timeout);
    
    if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
      throw new IOException(
          "Image transfer servlet at " + url +
          " failed with status code " + connection.getResponseCode() +
          "\nResponse message:\n" + connection.getResponseMessage());
    }
    
    long advertisedSize;
    String contentLength = connection.getHeaderField(CONTENT_LENGTH);
    if (contentLength != null) {
      advertisedSize = Long.parseLong(contentLength);
    } else {
      throw new IOException(CONTENT_LENGTH + " header is not provided " +
                            "by the namenode when trying to fetch " + str);
    }
    
    MD5Hash advertisedDigest = parseMD5Header(connection);
    long received = 0;
    InputStream stream = connection.getInputStream();
    MessageDigest digester = null;
    if (getChecksum) {
      digester = MD5Hash.getDigester();
      stream = new DigestInputStream(stream, digester);
    }
    boolean finishedReceiving = false;

    List<FileOutputStream> outputStreams = new ArrayList<FileOutputStream>();
    MD5Hash computedDigest = null;

    try {
      if (localPaths != null) {
        for (File f : localPaths) {
          try {
            if (f.exists()) {
              LOG.warn("Overwriting existing file " + f
                  + " with file downloaded from " + str);
            }
            outputStreams.add(new FileOutputStream(f));
          } catch (IOException ioe) {
            LOG.warn("Unable to download file " + f, ioe);
            dstStorage.reportErrorOnFile(f);
          }
        }
        
        if (outputStreams.isEmpty()) {
          throw new IOException(
              "Unable to download to any storage directory");
        }
      }
      
      int num = 1;
      while (num > 0) {
        num = stream.read(buf);
        if (num > 0) {
          received += num;
          for (FileOutputStream fos : outputStreams) {
            fos.write(buf, 0, num);
          }
        }
      }
      if(digester != null)
        computedDigest = new MD5Hash(digester.digest());
      finishedReceiving = true;
    } finally {
      stream.close();
      for (FileOutputStream fos : outputStreams) {
        fos.getChannel().force(true);
        fos.close();
      }
      if (finishedReceiving && received != advertisedSize) {
        // only throw this exception if we think we read all of it on our end
        // -- otherwise a client-side IOException would be masked by this
        // exception that makes it look like a server-side problem!
        throw new IOException("File " + str + " received length " + received +
                              " is not of the advertised size " +
                              advertisedSize);
      }
    }
    
    if (computedDigest != null) {
      if (advertisedDigest != null &&
          !computedDigest.equals(advertisedDigest)) {
        throw new IOException("File " + str + " computed digest " +
            computedDigest + " does not match advertised digest " + 
            advertisedDigest);
      }
      return computedDigest;
    } else {
      return null;
    }    
  }

  private static MD5Hash parseMD5Header(HttpURLConnection connection) {
    String header = connection.getHeaderField(MD5_HEADER);
    MD5Hash hash = (header != null) ? new MD5Hash(header) : null;
    return hash;
  }

}

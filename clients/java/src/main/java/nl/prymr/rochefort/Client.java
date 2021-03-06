package nl.prymr.rochefort;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static nl.prymr.rochefort.Util.convertStreamToString;
import static nl.prymr.rochefort.Util.readFully;

public class Client {
  private String urlGetMulti, urlGet, urlAppend, urlScan;

  public Client(String url) throws Exception {
    this(new URL(url));
  }

  public Client(URL url) {
    String prefix;

    if (url.getPort() != -1) {
      prefix = url.getProtocol() + "://" + url.getHost() + ":" + url.getPort();
    } else {
      prefix = url.getProtocol() + "://" + url.getHost();
    }
    prefix += url.getPath();
    if (!url.getPath().endsWith("/")) {
      prefix += "/";
    }

    this.urlGet = prefix + "get";
    this.urlGetMulti = prefix + "getMulti";
    this.urlAppend = prefix + "append";
    this.urlScan = prefix + "scan";
  }

  public static long append(String urlSet, String namespace, String id, byte[] data)
      throws Exception {
    HttpResponse<InputStream> response =
        Unirest.post(urlSet)
            .queryString("id", id)
            .queryString("namespace", namespace)
            .body(data)
            .asBinary();
    if (response.getStatus() != 200) {
      throw new Exception(
          "status code "
              + response.getStatus()
              + " url: "
              + urlSet
              + " body: "
              + convertStreamToString(response.getRawBody()));
    }
    byte[] ret = readFully(response.getBody(), -1, true);

    return new JsonNode(new String(ret)).getObject().getLong("offset");
  }

  public static List<byte[]> getMulti(String urlGetMulti, String namespace, long[] listOfOffsets)
      throws Exception {
    return getMulti(urlGetMulti, namespace, Util.listOfLongsToBytes(listOfOffsets));
  }

  public static byte[] get(String urlGet, String namespace, long offset) throws Exception {
    HttpResponse<InputStream> response =
        Unirest.get(urlGet)
            .queryString("namespace", namespace)
            .queryString("offset", offset)
            .asBinary();
    if (response.getStatus() != 200) {
      throw new Exception(
          "status code "
              + response.getStatus()
              + " url: "
              + urlGet
              + " namespace: "
              + namespace
              + " body: "
              + convertStreamToString(response.getRawBody()));
    }

    return readFully(response.getRawBody(), -1, true);
  }

  public static List<byte[]> getMulti(
      String urlGetMulti, String namespace, byte[] encodedListOfOffsets) throws Exception {

    HttpResponse<InputStream> response =
        Unirest.post(urlGetMulti)
            .queryString("namespace", namespace)
            .body(encodedListOfOffsets)
            .asBinary();
    if (response.getStatus() != 200) {
      throw new Exception(
          "status code "
              + response.getStatus()
              + " url: "
              + urlGetMulti
              + "namespace: "
              + namespace
              + " body: "
              + convertStreamToString(response.getRawBody()));
    }

    List<byte[]> out = new ArrayList<byte[]>(encodedListOfOffsets.length / 8);

    byte[] data = readFully(response.getRawBody(), -1, true);
    int offset = 0;

    while (true) {
      if (data.length < offset + 4) return out;

      int len = Util.aByteToInt(data, offset);
      offset += 4;
      if (len == 0) {
        throw new Exception("read errror url: " + urlGetMulti);
      }

      byte[] stored = new byte[len];
      System.arraycopy(data, offset, stored, 0, len);
      out.add(stored);

      offset += len;
      if (offset == data.length) break;
    }
    return out;
  }

  public static void scan(String urlGetScan, String namespace, ScanConsumer consumer)
      throws Exception {
    URL url = new URL((urlGetScan + "?namespace=" + namespace));

    // XXX: Unirest reads the whole body, which makes the scan useless
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();

    connection.setRequestMethod("GET");
    connection.setDoOutput(false);
    connection.setConnectTimeout(consumer.getConnectTimeout());
    connection.setReadTimeout(consumer.getReadTimeout());
    connection.setRequestProperty("Connection", "close");

    InputStream inputStream = null;
    InputStreamReader reader = null;

    try {
      inputStream = connection.getInputStream();
      byte[] buffer = new byte[65535];
      byte[] header = new byte[12];
      DataInputStream is = new DataInputStream(inputStream);

      while (true) {
        try {
          is.readFully(header, 0, header.length);
        } catch (EOFException e) {
          break;
        }
        int len = Util.aByteToInt(header, 0);
        long offset = Util.abyteToLong(header, 4);
        if (buffer.length < len) {
          buffer = new byte[len];
        }
        is.readFully(buffer, 0, len);
        consumer.accept(buffer, len, offset);
      }

    } catch (Exception e) {
      int code = connection.getResponseCode();
      throw new Exception(
          "status code "
              + code
              + " url: "
              + urlGetScan
              + " namespace: "
              + namespace
              + " exception: "
              + e.getMessage());
    } finally {
      if (reader != null) {
        reader.close();
      }
      if (inputStream != null) {
        inputStream.close();
      }
      connection.disconnect();
    }
  }

  public long append(String id, byte[] data) throws Exception {
    return append("", id, data);
  }

  public long append(String namespace, String id, byte[] data) throws Exception {
    return append(this.urlAppend, namespace, id, data);
  }

  public byte[] get(long offset) throws Exception {
    return get("", offset);
  }

  public byte[] get(String namespace, long offset) throws Exception {
    return get(this.urlGet, namespace, offset);
  }

  public List<byte[]> getMulti(long[] listOfOffsets) throws Exception {
    return getMulti("", listOfOffsets);
  }

  public List<byte[]> getMulti(byte[] encodedListOfOffsets) throws Exception {
    return getMulti("", encodedListOfOffsets);
  }

  public List<byte[]> getMulti(String namespace, long[] listOfOffsets) throws Exception {
    return getMulti(this.urlGetMulti, namespace, listOfOffsets);
  }

  public List<byte[]> getMulti(String namespace, byte[] encodedListOfOffsets) throws Exception {
    return getMulti(this.urlGetMulti, namespace, encodedListOfOffsets);
  }

  public void scan(ScanConsumer consumer) throws Exception {
    scan(this.urlScan, "", consumer);
  }

  public void scan(String namespace, ScanConsumer consumer) throws Exception {
    scan(this.urlScan, namespace, consumer);
  }

  public abstract static class ScanConsumer {
    public int getConnectTimeout() {
      return 1000;
    }

    public int getReadTimeout() {
      return 1000;
    }

    public abstract void accept(byte[] buffer, int length, long rochefortOffset) throws Exception;
  }
}

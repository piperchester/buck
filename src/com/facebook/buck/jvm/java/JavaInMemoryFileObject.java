/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java;

import com.facebook.buck.zip.CustomZipOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;

import javax.tools.SimpleJavaFileObject;

/**
 * A {@link SimpleJavaFileObject} implementation that forwards the content of the file to a Jar
 * output stream instead of writing it to disk. Since the Jar can be shared between multiple
 * threads, a semaphore is used to ensure exclusive access to the output stream.
 */
public class JavaInMemoryFileObject extends JarFileObject {
  // Bump the initial buffer size because usual file sizes using this are way more than 4K and the
  // default buffer size of a ByteArrayOutputStream is just 32
  private static final int BUFFER_SIZE = 4096;
  private static final String ALREADY_OPENED = "Output stream or writer has already been opened.";

  private boolean isOpened = false;
  private boolean isWritten = false;
  private final CustomZipOutputStream jarOutputStream;
  private final Semaphore jarFileSemaphore;
  private final ByteArrayOutputStream bos = new ByteArrayOutputStream(BUFFER_SIZE);

  public JavaInMemoryFileObject(
      URI uri,
      String pathInJar,
      Kind kind,
      CustomZipOutputStream jarOutputStream,
      Semaphore jarFileSemaphore) {
    super(uri, pathInJar, kind);
    this.jarOutputStream = jarOutputStream;
    this.jarFileSemaphore = jarFileSemaphore;
  }

  @Override
  public InputStream openInputStream() throws IOException {
    if (!isWritten) {
      throw new FileNotFoundException(uri.toString());
    }
    return new ByteArrayInputStream(bos.toByteArray());
  }

  @Override
  public synchronized OutputStream openOutputStream() throws IOException {
    if (isOpened) {
      throw new IOException(ALREADY_OPENED);
    }
    isOpened = true;
    final ZipEntry entry = JavaInMemoryFileManager.createEntry(getName());
    return new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        bos.write(b);
      }

      @Override
      public void close() throws IOException {
        bos.close();
        jarFileSemaphore.acquireUninterruptibly();
        try {
          jarOutputStream.putNextEntry(entry);
          jarOutputStream.write(bos.toByteArray());
          jarOutputStream.closeEntry();
          isWritten = true;
        } finally {
          jarFileSemaphore.release();
        }
      }
    };
  }

  @Override
  public Reader openReader(boolean ignoreEncodingErrors) throws IOException {
    return new InputStreamReader(openInputStream());
  }

  @Override
  public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
    return new String(bos.toByteArray());
  }

  @Override
  public Writer openWriter() throws IOException {
    return new OutputStreamWriter(this.openOutputStream());
  }
}

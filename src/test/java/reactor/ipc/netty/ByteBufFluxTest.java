/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import io.netty.buffer.ByteBuf;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Unit tests for {@link ByteBufFlux}
 *
 * @author Silvano Riz
 */
public class ByteBufFluxTest {

    private static File temporaryDirectory;

    @BeforeClass
    public static void createTempDir() {
        temporaryDirectory = createTemporaryDirectory();
    }

    @AfterClass
    public static void deleteTempDir() {
        deleteTemporaryDirectoryRecursively(temporaryDirectory);
    }

    @Test
    public void testFromPath() throws Exception {

        // Create a temporary file with some binary data that will be read in chunks using the ByteBufFlux
        final int chunkSize = 3;
        final Path tmpFile = new File(temporaryDirectory, "content.in").toPath();
        final byte[] data = new byte[]{0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7, 0x8, 0x9};
        Files.write(tmpFile, data);
        // Make sure the file is 10 bytes (i.e. the same ad the data length)
        Assert.assertEquals(data.length, Files.size(tmpFile));

        // Use the ByteBufFlux to read the file in chunks of 3 bytes max and write them into a ByteArrayOutputStream for verification
        final Iterator<ByteBuf> it = ByteBufFlux.fromPath(tmpFile, chunkSize)
                                                .retain()
                                                .toIterable()
                                                .iterator();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (it.hasNext()) {
            ByteBuf bb = it.next();
            byte[] read = new byte[bb.readableBytes()];
            bb.readBytes(read);
            bb.release();
            Assert.assertEquals(0, bb.readableBytes());
            out.write(read);
        }

        // Verify that we read the file.
        Assert.assertArrayEquals(data, out.toByteArray());
        System.out.println(Files.exists(tmpFile));

    }

    private static File createTemporaryDirectory() {
        try {
            final File tempDir = File.createTempFile("ByteBufFluxTest", "", null);
            tempDir.delete();
            tempDir.mkdir();
            return tempDir;
        } catch (Exception e) {
            throw new RuntimeException("Error creating the temporary directory", e);
        }
    }

    private static void deleteTemporaryDirectoryRecursively(final File file) {
        if (temporaryDirectory == null || !temporaryDirectory.exists()){
            return;
        }
        final File[] files = file.listFiles();
        if (files != null) {
            for (File childFile : files) {
                deleteTemporaryDirectoryRecursively(childFile);
            }
        }
        file.delete();
    }

}
package io.github.escosis.hwtfaceswap.protocol;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 文档提供方读取失败时，载入器应清理自己创建的部分副本。 */
public class HwtLoadFailureTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void io读取失败清理部分副本() throws Exception {
        File privateFiles = temporary.newFolder("private");
        InputStream input = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("合成读取失败"); }
        };
        try {
            HwtSource.load(input, privateFiles);
            fail("应保留原始读取异常");
        } catch(IOException expected) {
            assertEquals("合成读取失败", expected.getMessage());
        }
        assertNoPartialCopies(privateFiles);
    }

    @Test public void 未知读取异常仍清理部分副本() throws Exception {
        File privateFiles = temporary.newFolder("private");
        RuntimeException failure = new IllegalStateException("合成提供方异常");
        InputStream input = new InputStream() {
            @Override public int read() { throw failure; }
        };
        try {
            HwtSource.load(input, privateFiles);
            fail("应原样抛出提供方异常");
        } catch(RuntimeException expected) {
            assertSame(failure, expected);
        }
        assertNoPartialCopies(privateFiles);
    }

    private void assertNoPartialCopies(File privateFiles) {
        File parent = new File(privateFiles, "local-hwt");
        assertTrue(parent.isDirectory());
        File[] contents = parent.listFiles();
        assertNotNull(contents);
        assertEquals(0, contents.length);
    }
}

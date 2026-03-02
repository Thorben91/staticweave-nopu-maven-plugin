package net.unit8.maven.plugins;

import org.apache.maven.plugin.logging.Log;
import org.eclipse.persistence.internal.helper.Helper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class LogWriterAdditionalTest {

    private Log log;
    private LogWriter logWriter;

    @BeforeEach
    void setUp() {
        log = mock(Log.class);
        logWriter = new LogWriter(log);
    }

    @Test
    void writeWithOffsetAndLen_delegatesToWriteString() {
        logWriter.write("Hello World", 6, 5);
        logWriter.write(Helper.cr());

        verify(log).info((CharSequence) argThat(cs -> cs.toString().equals("World")));
    }

    @Test
    void writeCharArrayWithOffsetAndLen_delegatesToWriteString() {
        char[] chars = {'H', 'e', 'l', 'l', 'o'};
        logWriter.write(chars, 2, 3);
        logWriter.write(Helper.cr());

        verify(log).info((CharSequence) argThat(cs -> cs.toString().equals("llo")));
    }

    @Test
    void flush_doesNotCallLogInfoWhenBufferIsEmpty() {
        logWriter.flush();

        verify(log, never()).info(any(CharSequence.class));
    }

    @Test
    void flush_clearsBufferAfterInfo() {
        logWriter.write("first");
        logWriter.flush();
        logWriter.write("second");
        logWriter.flush();

        verify(log).info((CharSequence) argThat(cs -> cs.toString().equals("first")));
        verify(log).info((CharSequence) argThat(cs -> cs.toString().equals("second")));
        verify(log, times(2)).info(any(CharSequence.class));
    }

    @Test
    void multipleLineWrite_flushesEachLineToInfo() {
        String cr = Helper.cr();
        logWriter.write("lineA");
        logWriter.write(cr);
        logWriter.write("lineB");
        logWriter.write(cr);

        verify(log).info((CharSequence) argThat(cs -> cs.toString().equals("lineA")));
        verify(log).info((CharSequence) argThat(cs -> cs.toString().equals("lineB")));
    }

    @Test
    void write_appendsToBufferWhenNotCr() {
        logWriter.write("foo");
        logWriter.write("bar");
        logWriter.flush();

        verify(log).info((CharSequence) argThat(cs -> cs.toString().equals("foobar")));
    }
}

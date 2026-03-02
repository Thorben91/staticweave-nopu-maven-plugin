package net.unit8.maven.plugins;

import org.apache.maven.plugin.logging.Log;
import org.eclipse.persistence.internal.helper.Helper;

import java.io.StringWriter;

/**
 * A {@link java.io.StringWriter} that bridges EclipseLink's {@link java.io.PrintWriter}-based
 * logging to Maven's {@link Log} interface.
 *
 * <p>Characters are buffered until a newline is written, at which point
 * {@link #flush()} is called and the accumulated line is forwarded to
 * {@link Log#info(CharSequence)}.</p>
 */
public class LogWriter extends StringWriter {
    private Log log;

    /**
     * Creates a new {@code LogWriter} that forwards lines to the given Maven log.
     *
     * @param log the Maven log to write output to
     */
    public LogWriter(Log log) {
        super();
        this.log = log;
    }

    @Override
    public void write(String str) {
        if (!Helper.cr().equals(str)) {
            getBuffer().append(str);
        } else {
            flush();
        }
    }

    @Override
    public void write(String str, int off, int len) {
        write(str.substring(off, off + len));
    }

    @Override
    public void write(char[] cbuf, int off, int len) {
        write(new String(cbuf, off, len));
    }

    @Override
    public void flush() {
        if (getBuffer().length() > 0) {
            log.info(getBuffer().toString());
            getBuffer().setLength(0);
        }
    }
}

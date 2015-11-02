package com.sourcegraph.toolchain.core;

import java.io.IOException;

/**
 * This interface is responsible for collecting and writing references and definitions produced by grapher
 */
public interface GraphWriter {

    /**
     * Writes reference
     * @param r reference to write
     * @throws IOException
     */
    void writeRef(Ref r) throws IOException;

    /**
     * Writes definition
     * @param s definition to write
     * @throws IOException
     */
    void writeDef(Def s) throws IOException;

    /**
     * Flush underlying streams
     * @throws IOException
     */
    void flush() throws IOException;
}

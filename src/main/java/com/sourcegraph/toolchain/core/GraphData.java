package com.sourcegraph.toolchain.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of graph writer that collects references and definitions and then writes them as JSON
 */
public class GraphData implements GraphWriter {

    public final List<Def> Defs = new ArrayList<>();
    public final List<Ref> Refs = new ArrayList<>();

    @Override
    public void writeRef(Ref r) throws IOException {
        Refs.add(r);
    }

    @Override
    public void writeDef(Def s) throws IOException {
        Defs.add(s);
    }

    @Override
    public void flush() throws IOException {
    }

}

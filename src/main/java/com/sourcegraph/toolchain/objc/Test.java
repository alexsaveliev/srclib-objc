package com.sourcegraph.toolchain.objc;

import com.sourcegraph.toolchain.core.GraphData;
import com.sourcegraph.toolchain.core.GraphWriter;

import java.util.Arrays;

public class Test {

    public static void main(String args[]) throws Exception {

        GraphWriter writer = new GraphData();

        ObjCGraph graph = new ObjCGraph(writer);
        graph.process(Arrays.asList(args));
        writer.flush();

//        JSONUtil.writeJSON(writer);
    }
}

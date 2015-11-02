package com.sourcegraph.toolchain.objc;

import com.sourcegraph.toolchain.core.GraphWriter;
import com.sourcegraph.toolchain.core.PathUtil;
import com.sourcegraph.toolchain.objc.antlr4.ObjCLexer;
import com.sourcegraph.toolchain.objc.antlr4.ObjCParser;
import org.antlr.v4.runtime.ANTLRFileStream;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ObjCGraph {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObjCGraph.class);

    GraphWriter writer;

    Map<String, String> globalVars = new HashMap<>();
    // class name -> (variable -> type)
    Map<String, Map<String, String>> instanceVars = new HashMap<>();

    Set<String> functions = new HashSet<>();
    Set<String> types = new HashSet<>();

    private Set<String> visited = new HashSet<>();
    private Set<String> files;

    public ObjCGraph(GraphWriter writer) {
        this.writer = writer;
    }

    public void process(Collection<String> files) {
        this.files = new HashSet<>();
        for (String file : files) {
            this.files.add(PathUtil.relativizeCwd(file));
        }
        for (String file : files) {
            process(file, null);
        }
    }

    protected void process(String file, String from) {
        if (from != null) {
            file = PathUtil.concat(new File(from).getParentFile(), file).getPath();
        }
        file = PathUtil.relativizeCwd(file);
        if (visited.contains(file)) {
            return;
        }
        if (!files.contains(file)) {
            return;
        }
        LOGGER.info("Processing {}", file);
        visited.add(file);
        try {
            FileGrapher extractor = new FileGrapher(this, file);

            CharStream stream = new ANTLRFileStream(file);
            ObjCLexer lexer = new ObjCLexer(stream);
            lexer.removeErrorListeners();
            lexer.addErrorListener(extractor);

            CommonTokenStream tokens = new CommonTokenStream(lexer);
            ObjCParser parser = new ObjCParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(extractor);
            ObjCParser.Translation_unitContext tree = parser.translation_unit(); // parse
            ParseTreeWalker walker = new ParseTreeWalker(); // create standard walker
            walker.walk(extractor, tree); // initiate walk of tree with listener
        } catch (IOException e) {
            LOGGER.warn("Failed to process {}: {}", file, e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Failed to process {} - unexpected error", file, e);
        }
    }
}
package com.sourcegraph.toolchain.objc;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class SourceTree {

    private Collection<String> files;

    private Map<String, Collection<String>> dependencies = new HashMap<>();

    public SourceTree(Collection<String> files) {
        for (String file : files) {
            collectDependencies(file);
        }

        for (String file : files) {
            collectDependencies(file);
        }
    }

    private void collectDependencies(String fileName) {
        File file = new File(fileName);
        if (!file.isFile()) {
            return;
        }
        try {
            String source = IOUtils.toString(new FileInputStream(file));
            collectImports(source, "#import");
            collectImports(source, "#include");
        } catch (IOException ex) {
            // ignore
        }
    }

    private void collectImports(String source, String prefix) {
        int offset = 0;
        int start;
        int l = source.length();
        while ((start = source.indexOf(prefix, offset)) >= 0) {
            offset = start + prefix.length();
            while (offset < l) {
                char c = source.charAt(offset);
                if (c == ' ' || c == '\t') {
                    offset++;
                }
            }
            if (offset == l) {
                return;
            }
            char end = source.charAt(offset) == '"' ? '"' : '>';
            int pos = source.indexOf(end, offset + 1);
            if (pos < 0) {
                return;
            }
            String dep = source.substring(offset + 1, pos);
            dep = dep.substring(1, dep.length() - 1);
        }
    }
}


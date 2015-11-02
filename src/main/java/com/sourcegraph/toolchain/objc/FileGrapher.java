package com.sourcegraph.toolchain.objc;

import com.sourcegraph.toolchain.core.Def;
import com.sourcegraph.toolchain.core.DefKey;
import com.sourcegraph.toolchain.core.Ref;
import com.sourcegraph.toolchain.objc.antlr4.ObjCBaseListener;
import com.sourcegraph.toolchain.objc.antlr4.ObjCLexer;
import com.sourcegraph.toolchain.objc.antlr4.ObjCParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class FileGrapher extends ObjCBaseListener implements ANTLRErrorListener {

    private static Logger LOGGER = LoggerFactory.getLogger(FileGrapher.class);

    private static final String[] PREDEFINED_TYPES = new String[] {
        "id", "void", "char", "short", "int", "long", "float", "double", "signed", "unsigned"
    };

    private ObjCGraph graph;

    private String file;

    private String currentClassName;
    private String currentMethodName;

    private Map<String, String> paramsVars = new HashMap<>();
    private Stack<Map<String, Var>> localVars = new Stack<>();

    private int blockCounter;

    public FileGrapher(ObjCGraph graph, String file) {
        this.graph = graph;
        this.file = file;
    }

    @Override
    public void enterPreprocessor_declaration(ObjCParser.Preprocessor_declarationContext ctx) {

        String prefix;
        if (ctx.IMPORT() != null) {
            prefix = "#import";
        } else if (ctx.INCLUDE() != null) {
            prefix = "#include";
        } else {
            return;
        }
        String fileName = ctx.getText();
        if (!fileName.startsWith(prefix)) {
            return;
        }
        fileName = fileName.substring(prefix.length()).trim();
        // cut <> or ""
        fileName = fileName.substring(1, fileName.length() - 1);
        graph.process(fileName, file);
    }

    @Override
    public void enterClass_implementation(ObjCParser.Class_implementationContext ctx) {

        localVars.push(new HashMap<>());
        currentClassName = ctx.class_name().getText();

        Ref interfaceRef = ref(ctx.class_name());
        interfaceRef.defKey = new DefKey(null, currentClassName);
        emit(interfaceRef);

        // registering "self" variable
        Map<String, String> currentClassVars = graph.instanceVars.get(currentClassName);
        if (currentClassVars == null) {
            currentClassVars = new HashMap<>();
            graph.instanceVars.put(currentClassName, currentClassVars);
        }
        currentClassVars.put("self", currentClassName);
    }

    @Override
    public void exitClass_implementation(ObjCParser.Class_implementationContext ctx) {
        localVars.pop();
        currentClassName = null;
    }

    @Override
    public void enterClass_method_definition(ObjCParser.Class_method_definitionContext ctx) {
        currentMethodName = getFuncName(ctx.method_definition().method_selector());
        localVars.push(new HashMap<>());
        processMethodDefinition(ctx.method_definition(), true);
    }

    @Override
    public void exitClass_method_definition(ObjCParser.Class_method_definitionContext ctx) {
        currentMethodName = null;
        localVars.pop();
        paramsVars.clear();
    }

    @Override
    public void enterInstance_method_definition(ObjCParser.Instance_method_definitionContext ctx) {
        currentMethodName = getFuncName(ctx.method_definition().method_selector());
        localVars.push(new HashMap<>());
        processMethodDefinition(ctx.method_definition(), false);
    }

    @Override
    public void exitInstance_method_definition(ObjCParser.Instance_method_definitionContext ctx) {
        currentMethodName = null;
        localVars.pop();
        paramsVars.clear();
    }

    @Override
    public void enterDeclaration(ObjCParser.DeclarationContext ctx) {
        List<ObjCParser.Storage_class_specifierContext> storageClassSpecifierContexts =
                ctx.declaration_specifiers().storage_class_specifier();
        boolean extern = storageClassSpecifierContexts != null && !storageClassSpecifierContexts.isEmpty() &&
                storageClassSpecifierContexts.get(0).getText().equals("extern");

        String typeName = null;
        for (ObjCParser.Type_specifierContext typeSpecifierContext : ctx.declaration_specifiers().type_specifier()) {
            String type = processTypeSpecifier(typeSpecifierContext);
            if (type != null) {
                typeName = type;
            }
        }
        ObjCParser.Init_declarator_listContext initDeclaratorListContext = ctx.init_declarator_list();
        if (initDeclaratorListContext == null) {
            List<ObjCParser.Type_specifierContext> typeSpecifierContexts = ctx.declaration_specifiers().type_specifier();
            if (typeSpecifierContexts.isEmpty()) {
                return;
            }
            ObjCParser.Type_specifierContext ident = typeSpecifierContexts.get(typeSpecifierContexts.size() - 1);
            if (ident.struct_or_union_specifier() == null &&
                    ident.class_name() == null &&
                    ident.enum_specifier() == null) {
                Def varDef = def(ident, "VAR");
                Map<String, String> vars = null;
                String defKey;
                if (currentClassName == null) {
                    vars = graph.globalVars;
                    defKey = varDef.name;
                } else {
                    if (currentMethodName == null) {
                        vars = graph.instanceVars.get(currentClassName);
                        if (vars == null) {
                            vars = new HashMap<>();
                            graph.instanceVars.put(currentClassName, vars);
                        }
                        defKey = currentDefKey(varDef.name);
                    } else {
                        Var var = new Var(varDef.name, typeName);
                        localVars.peek().put(varDef.name, var);
                        defKey = var.defKey;
                    }
                }
                varDef.defKey = new DefKey(null, defKey);
                emit(varDef);
                if (vars != null) {
                    vars.put(varDef.name, typeName);
                }
            }
        } else {
            for (ObjCParser.Init_declaratorContext context : initDeclaratorListContext.init_declarator()) {
                ObjCParser.DeclaratorContext declaratorContext = context.declarator();
                ParserRuleContext ident = ident(declaratorContext);
                if (ident == null) {
                    continue;
                }
                if (declaratorContext.direct_declarator().identifier() == null) {
                    // NSLog(a), looking at "(a)" here
                    Ref argRef = ref(ident);
                    String defKey = currentDefKey(ident.getText());
                    argRef.defKey = new DefKey(null, defKey);
                    emit(argRef);
                    continue;
                }

                if (extern) {
                    Ref externRef = ref(ident);
                    externRef.defKey = new DefKey(null, ident.getText());
                    emit(externRef);
                    graph.globalVars.put(ident.getText(), typeName);
                } else {
                    Def varDef = def(ident, "VAR");
                    Map<String, String> vars = null;
                    String defKey;
                    if (currentClassName == null) {
                        vars = graph.globalVars;
                        defKey = varDef.name;
                    } else {
                        if (currentMethodName == null) {
                            vars = graph.instanceVars.get(currentClassName);
                            if (vars == null) {
                                vars = new HashMap<>();
                                graph.instanceVars.put(currentClassName, vars);
                            }
                            defKey = currentDefKey(varDef.name);
                        } else {
                            Var var = new Var(varDef.name, typeName);
                            localVars.peek().put(varDef.name, var);
                            defKey = var.defKey;
                        }
                    }
                    varDef.defKey = new DefKey(null, defKey);
                    emit(varDef);
                    if (vars != null) {
                        vars.put(varDef.name, typeName);
                    }
                }
            }
        }
    }

    @Override
    public void enterMessage_expression(ObjCParser.Message_expressionContext ctx) {

        // TODO: support property getter/setter methods
        if (currentMethodName == null) {
            return;
        }

        ObjCParser.Message_selectorContext messageSelectorContext = ctx.message_selector();

        String funcName = getFuncName(messageSelectorContext);
        String receiver = ctx.receiver().getText();
        String messageKey;

        if (receiver.equals("self") || receiver.equals("super")) {
            // TODO: separate super
            messageKey = currentClassName + '/' + funcName;
        } else {
            // class method?
            Var var = getLocalVariable(receiver);
            if (var != null) {
                messageKey = var.type + '/' + funcName;
            } else {
                String type = paramsVars.get(receiver);
                if (type != null) {
                    messageKey = type + '/' + funcName;
                } else {
                    Map<String, String> currentInstanceVars = graph.instanceVars.get(currentClassName);
                    type = currentInstanceVars != null ? currentInstanceVars.get(receiver) : null;
                    if (type != null) {
                        messageKey = type + '/' + funcName;
                    } else {
                        type = graph.globalVars.get(receiver);
                        if (type != null) {
                            messageKey = type + '/' + funcName;
                        } else {
                            messageKey = guessMessageKey(ctx.receiver(), funcName);
                        }
                    }
                }
            }
        }
        if (messageKey != null) {
            ParserRuleContext fnCallCtx;
            if (messageSelectorContext.selector() == null) {
                fnCallCtx = messageSelectorContext.keyword_argument(0).selector();
            } else {
                fnCallCtx = messageSelectorContext.selector();
            }
            Ref fnCallRef = ref(fnCallCtx);
            fnCallRef.defKey = new DefKey(null, messageKey);
            emit(fnCallRef);
        }
    }

    private String guessMessageKey(ParseTree receiver, String funcName) {
        ObjCParser.ReceiverContext messageReceiver = getMessageReceiver(receiver);
        if (messageReceiver != null) {
            return guessMessageKey(messageReceiver, funcName);
        }
        String text = receiver.getText();
        int len = text.length();
        int state = 0;
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            switch (state) {
                case 0:
                    if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                        state = 1;
                    } else {
                        return null;
                    }
                    break;
                case 1:
                    if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9') {
                        // ok
                    } else {
                        return null;
                    }
            }
        }
        return text + '/' + funcName;
    }

    private ObjCParser.ReceiverContext getMessageReceiver(ParseTree ctx) {
        if (ctx instanceof ObjCParser.Message_expressionContext) {
            return ((ObjCParser.Message_expressionContext) ctx).receiver();
        }
        if (ctx.getChildCount() == 1) {
            return getMessageReceiver(ctx.getChild(0));
        }
        return null;
    }

    @Override
    public void enterClass_interface(ObjCParser.Class_interfaceContext ctx) {

        // interface definition
        Def interfaceDef = def(ctx.class_name(), "CLASS");
        interfaceDef.defKey = new DefKey(null, interfaceDef.name);
        emit(interfaceDef);

        currentClassName = interfaceDef.name;
        Map<String, String> currentClassVars = graph.instanceVars.get(currentClassName);
        if (currentClassVars == null) {
            currentClassVars = new HashMap<>();
            graph.instanceVars.put(currentClassName, currentClassVars);
        }

        graph.types.add(interfaceDef.name);

        // reference to superclass if any
        ObjCParser.Superclass_nameContext superclassNameContext = ctx.superclass_name();
        if (superclassNameContext != null) {
            Ref superInterfaceRef = ref(superclassNameContext);
            superInterfaceRef.defKey = new DefKey(null, superclassNameContext.getText());
            emit(superInterfaceRef);
        }

        // reference to protocols if any
        ObjCParser.Protocol_reference_listContext protocolReferenceListContext = ctx.protocol_reference_list();
        processProtocolReferences(protocolReferenceListContext);

        // instance variables
        ObjCParser.Instance_variablesContext instanceVariablesContext = ctx.instance_variables();
        processInstanceVariables(currentClassVars, instanceVariablesContext);

        // class and instance methods
        ObjCParser.Interface_declaration_listContext interfaceDeclarationListContext = ctx.interface_declaration_list();
        processDeclarationList(interfaceDeclarationListContext);
    }

    @Override
    public void exitClass_interface(ObjCParser.Class_interfaceContext ctx) {
        currentClassName = null;
    }

    @Override
    public void enterCategory_interface(ObjCParser.Category_interfaceContext ctx) {

        String interfaceName = ctx.class_name().getText();
        Ref interfaceRef = ref(ctx.class_name());
        interfaceRef.defKey = new DefKey(null, interfaceName);
        emit(interfaceRef);

        if (ctx.category_name() != null) {
            Def categoryDef = def(ctx.category_name(), "CLASS");
            categoryDef.defKey = new DefKey(null, categoryDef.name);
            emit(categoryDef);
            graph.types.add(categoryDef.name);
        }

        currentClassName = interfaceName;
        Map<String, String> currentClassVars = graph.instanceVars.get(currentClassName);
        if (currentClassVars == null) {
            currentClassVars = new HashMap<>();
            graph.instanceVars.put(currentClassName, currentClassVars);
        }

        graph.types.add(interfaceName);

        // reference to protocols if any
        ObjCParser.Protocol_reference_listContext protocolReferenceListContext = ctx.protocol_reference_list();
        processProtocolReferences(protocolReferenceListContext);

        // instance variables
        ObjCParser.Instance_variablesContext instanceVariablesContext = ctx.instance_variables();
        processInstanceVariables(currentClassVars, instanceVariablesContext);

        // class and instance methods
        ObjCParser.Interface_declaration_listContext interfaceDeclarationListContext = ctx.interface_declaration_list();
        processDeclarationList(interfaceDeclarationListContext);
    }

    @Override
    public void exitCategory_interface(ObjCParser.Category_interfaceContext ctx) {
        currentClassName = null;
    }

    @Override
    public void enterProtocol_declaration(ObjCParser.Protocol_declarationContext ctx) {
        // TODO
    }

    @Override
    public void exitProtocol_declaration(ObjCParser.Protocol_declarationContext ctx) {
        currentClassName = null;
    }

    @Override
    public void enterProtocol_declaration_list(ObjCParser.Protocol_declaration_listContext ctx) {

        ObjCParser.Protocol_listContext protocolListContext = ctx.protocol_list();
        if (protocolListContext != null) {
            List<ObjCParser.Protocol_nameContext> protocolNameContexts = protocolListContext.protocol_name();
            if (protocolNameContexts != null) {
                for (ObjCParser.Protocol_nameContext protocolNameContext : protocolNameContexts) {
                    Ref protocolRef = ref(protocolNameContext);
                    protocolRef.defKey = new DefKey(null, protocolNameContext.getText());
                    emit(protocolRef);
                }
            }
        }
    }

    @Override
    public void enterClass_declaration_list(ObjCParser.Class_declaration_listContext ctx) {

        ObjCParser.Class_listContext classListContext = ctx.class_list();
        if (classListContext != null) {
            List<ObjCParser.Class_nameContext> classNameContexts = classListContext.class_name();
            if (classNameContexts != null) {
                for (ObjCParser.Class_nameContext classNameContext : classNameContexts) {
                    Ref classRef = ref(classNameContext);
                    classRef.defKey = new DefKey(null, classNameContext.getText());
                    emit(classRef);
                }
            }
        }
    }

    @Override
    public void enterFunction_definition(ObjCParser.Function_definitionContext ctx) {

        blockCounter = 0;

        ParserRuleContext ident = ident(ctx.declarator());
        if (ident == null) {
            return;
        }
        Def fnDef = def(ident, "METHOD");
        fnDef.defKey = new DefKey(null, fnDef.name);
        emit(fnDef);
        graph.functions.add(fnDef.name);

        currentMethodName = fnDef.name;

        ObjCParser.Declaration_specifiersContext declarationSpecifiersContext = ctx.declaration_specifiers();
        if (declarationSpecifiersContext != null) {
            for (ObjCParser.Type_specifierContext typeSpecifierContext : declarationSpecifiersContext.
                    type_specifier()) {
                processTypeSpecifier(typeSpecifierContext);
            }
        }

        ObjCParser.Block_parametersContext blockParametersContext = ctx.declarator().direct_declarator().
                block_parameters();
        if (blockParametersContext != null) {

            for (ObjCParser.Type_variable_declaratorContext typeVariableDeclaratorContext : blockParametersContext.
                    type_variable_declarator()) {
                String typeName = null;
                for (ObjCParser.Type_specifierContext typeSpecifierContext : typeVariableDeclaratorContext.
                        declaration_specifiers().type_specifier()) {
                    String type = processTypeSpecifier(typeSpecifierContext);
                    if (type != null) {
                        typeName = type;
                    }
                }
                Def argDef = def(ident(typeVariableDeclaratorContext.declarator()), "VAR");
                argDef.defKey = new DefKey(null, currentDefKey(argDef.name));
                emit(argDef);
                paramsVars.put(argDef.name, typeName);
            }
        }

        List<ObjCParser.Declarator_suffixContext> declaratorSuffixContexts = ctx.declarator().direct_declarator().
                declarator_suffix();
        if (declaratorSuffixContexts != null) {

            for (ObjCParser.Declarator_suffixContext declaratorSuffixContext : declaratorSuffixContexts) {
                ObjCParser.Parameter_listContext parameterListContext = declaratorSuffixContext.parameter_list();
                if (parameterListContext == null) {
                    continue;
                }
                for (ObjCParser.Parameter_declarationContext parameterDeclarationContext : parameterListContext.
                        parameter_declaration_list().parameter_declaration()) {
                    String typeName = null;
                    for (ObjCParser.Type_specifierContext typeSpecifierContext : parameterDeclarationContext.
                            declaration_specifiers().type_specifier()) {
                        String type = processTypeSpecifier(typeSpecifierContext);
                        if (type != null) {
                            typeName = type;
                        }
                    }

                    ident = ident(parameterDeclarationContext.declarator());
                    if (ident == null) {
                        List<ObjCParser.Type_specifierContext> typeSpecifierContexts = parameterDeclarationContext.
                                declaration_specifiers().type_specifier();
                        if (typeSpecifierContexts.isEmpty()) {
                            return;
                        }
                        ident = typeSpecifierContexts.get(typeSpecifierContexts.size() - 1);
                    }
                    Def argDef = def(ident, "VAR");
                    argDef.defKey = new DefKey(null, currentDefKey(argDef.name));
                    emit(argDef);
                    paramsVars.put(argDef.name, typeName);
                }
            }
        }

    }

    @Override
    public void exitFunction_definition(ObjCParser.Function_definitionContext ctx) {
        paramsVars.clear();
        currentMethodName = null;
    }

    @Override
    public void enterProperty_declaration(ObjCParser.Property_declarationContext ctx) {
        // TODO: custom getter and setter
        ObjCParser.Struct_declaratorContext structDeclaratorContext =
                ctx.struct_declaration().struct_declarator_list().struct_declarator(0);

        // property def
        Def propertyDef = def(structDeclaratorContext.declarator().direct_declarator(), "VAR");
        propertyDef.defKey = new DefKey(null, currentClassName + '/' + propertyDef.name);
        emit(propertyDef);

        // type refs
        for (ObjCParser.Type_specifierContext typeSpecifierContext : ctx.struct_declaration().
                specifier_qualifier_list().type_specifier()) {
            processTypeSpecifier(typeSpecifierContext);
        }
    }

    @Override
    public void enterPostfix_expression(ObjCParser.Postfix_expressionContext ctx) {

        ObjCParser.Primary_expressionContext primaryExpressionContext = ctx.primary_expression();
        String id = primaryExpressionContext.getText();
        if (primaryExpressionContext.IDENTIFIER() == null &&
                !id.equals("self") &&
                !id.equals("super")) {
            // we can't parse complex expressions yet
            return;
        }
        List<ObjCParser.IdentifierContext> identifierContext = ctx.identifier();
        List<ObjCParser.Argument_expression_listContext> argumentExpressionListContext = ctx.argument_expression_list();
        List<ObjCParser.ExpressionContext> expressionContext = ctx.expression();

        if (identifierContext.isEmpty() && argumentExpressionListContext.isEmpty() && expressionContext.isEmpty() &&
                ctx.getStop().getType() != ObjCLexer.RPAREN) {
            // a or a++ or a--
            if (id.equals("self")) {
                Ref varRef = ref(primaryExpressionContext);
                varRef.defKey = new DefKey(null, currentClassName + "/self");
                emit(varRef);

            }
            return;
        }
        if (!identifierContext.isEmpty()) {
            // a.b or a->b
            String varName = identifierContext.get(0).getText();
            String propertyKey = null;
            if (id.equals("self") || id.equals("super")) {
                // TODO: separate super?
                propertyKey = currentClassName + '/' + varName;
            } else {
                Var var = getLocalVariable(id);
                if (var != null) {
                    propertyKey = var.type + '/' + varName;
                } else {
                    String type = paramsVars.get(id);
                    if (type != null) {
                        propertyKey = type + '/' + varName;
                    } else {
                        Map<String, String> currentInstanceVars = graph.instanceVars.get(currentClassName);
                        type = currentInstanceVars != null ? currentInstanceVars.get(id) : null;
                        if (type != null) {
                            propertyKey = type + '/' + varName;
                        } else {
                            type = graph.globalVars.get(id);
                            if (type != null) {
                                propertyKey = type + '/' + varName;
                            }
                        }
                    }
                }
            }
            if (propertyKey != null) {
                // ref to method
                Ref propertyRef = ref(identifierContext.get(0));
                propertyRef.defKey = new DefKey(null, propertyKey);
                emit(propertyRef);
            }
            return;
        }
    }

    @Override
    public void enterPrimary_expression(ObjCParser.Primary_expressionContext ctx) {
        if (ctx.IDENTIFIER() == null) {
            return;
        }
        String id = ctx.IDENTIFIER().getText();

        // ref to variable?
        String key = null;
        Var var = getLocalVariable(id);
        if (var != null) {
            key = var.defKey;
        } else if (paramsVars.containsKey(id)) {
            key = currentDefKey(id);
        } else {
            Map<String, String> currentClassVars = graph.instanceVars.get(currentClassName);
            if (currentClassVars != null && currentClassVars.containsKey(id)) {
                key = currentClassName + '/' + id;
            } else { // global var or type
                key = id;
            }
        }
        Ref varOrTypeRef = ref(ctx.IDENTIFIER());
        varOrTypeRef.defKey = new DefKey(null, key);
        emit(varOrTypeRef);
    }

    @Override
    public void enterEnum_specifier(ObjCParser.Enum_specifierContext ctx) {

        if (ctx.type_name() != null) {
            Ref typeRef = ref(ctx.type_name());
            typeRef.defKey = new DefKey(null, ctx.type_name().getText());
            emit(typeRef);
        }

        String typeName;
        if (ctx.identifier() != null) {
            Def typeDef = def(ctx.identifier(), "ENUM");
            // TODO: encapsulate enums
            typeDef.defKey = new DefKey(null, typeDef.name);
            emit(typeDef);
            typeName = typeDef.name;
        } else {
            typeName = "int";
        }
        ObjCParser.Enumerator_listContext enumeratorListContext = ctx.enumerator_list();
        if (enumeratorListContext == null) {
            return;
        }
        for (ObjCParser.EnumeratorContext enumeratorContext : enumeratorListContext.enumerator()) {
            Def enumeratorDef = def(enumeratorContext.identifier(), "VAR");
            Map<String, String> vars = null;
            String defKey;
            if (currentClassName == null) {
                vars = graph.globalVars;
                defKey = enumeratorDef.name;
            } else {
                if (currentMethodName == null) {
                    vars = graph.instanceVars.get(currentClassName);
                    if (vars == null) {
                        vars = new HashMap<>();
                        graph.instanceVars.put(currentClassName, vars);
                    }
                    defKey = currentDefKey(enumeratorDef.name);
                } else {
                    Var var = new Var(enumeratorDef.name, typeName);
                    localVars.peek().put(enumeratorDef.name, var);
                    defKey = var.defKey;
                }
            }
            enumeratorDef.defKey = new DefKey(null, defKey);
            emit(enumeratorDef);
            if (vars != null) {
                vars.put(enumeratorDef.name, typeName);
            }
        }
    }

    @Override
    public void enterType_variable_declarator(ObjCParser.Type_variable_declaratorContext ctx) {
        ObjCParser.Direct_declaratorContext directDeclaratorContext = ctx.declarator().direct_declarator();

        Def varDef = def(directDeclaratorContext, "VAR");

        for (ObjCParser.Type_specifierContext typeSpecifierContext : ctx.declaration_specifiers().type_specifier()) {
            String type = processTypeSpecifier(typeSpecifierContext);
            if (type != null) {
                if (currentMethodName != null) {
                    // TODO
                    Var var = new Var(varDef.name, type);
                    localVars.peek().put(varDef.name, var);
                    varDef.defKey = new DefKey(null, var.defKey);
                } else {
                    if (currentClassName != null) {
                        // class
                        varDef.defKey = new DefKey(null, currentClassName + '/' + varDef.name);
                        graph.instanceVars.get(currentClassName).put(varDef.name, type);
                    } else {
                        // global
                        varDef.defKey = new DefKey(null, varDef.name);
                        graph.globalVars.put(varDef.name, type);
                    }
                }
            }
        }
        if (varDef.defKey != null) {
            emit(varDef);
        }
    }

    @Override
    public void enterFor_statement(ObjCParser.For_statementContext ctx) {
        localVars.push(new HashMap<>());

        ObjCParser.Declaration_specifiersContext declarationSpecifiersContext = ctx.declaration_specifiers();
        if (declarationSpecifiersContext == null) {
            return;
        }

        String typeName = null;
        for (ObjCParser.Type_specifierContext typeSpecifierContext : declarationSpecifiersContext.type_specifier()) {
            String type = processTypeSpecifier(typeSpecifierContext);
            if (type != null) {
                typeName = type;
            }
        }

        for (ObjCParser.Init_declaratorContext initDeclaratorContext : ctx.init_declarator_list().init_declarator()) {
            ParserRuleContext ident = ident(initDeclaratorContext.declarator());
            if (ident == null) {
                continue;
            }

            Def varDef = def(ident, "VAR");
            Var var = new Var(varDef.name, typeName);
            localVars.peek().put(varDef.name, var);
            varDef.defKey = new DefKey(null, var.defKey);
            emit(varDef);
        }
    }

    @Override
    public void exitFor_statement(ObjCParser.For_statementContext ctx) {
        localVars.pop();
    }

    @Override
    public void enterCatch_statement(ObjCParser.Catch_statementContext ctx) {
        localVars.push(new HashMap<>());
    }

    @Override
    public void exitCatch_statement(ObjCParser.Catch_statementContext ctx) {
        localVars.pop();
    }

    @Override
    public void enterCompound_statement(ObjCParser.Compound_statementContext ctx) {
        localVars.push(new HashMap<>());
        blockCounter++;
    }

    @Override
    public void exitCompound_statement(ObjCParser.Compound_statementContext ctx) {
        localVars.pop();
    }

    @Override
    public void enterMethod_definition(ObjCParser.Method_definitionContext ctx) {

    }

    @Override
    public void enterCast_expression(ObjCParser.Cast_expressionContext ctx) {
        ObjCParser.Type_nameContext typeNameContext = ctx.type_name();
        if (typeNameContext == null) {
            return;
        }
        Ref typeRef = ref(typeNameContext);
        typeRef.defKey = new DefKey(null, typeNameContext.getText());
        emit(typeRef);
    }

    protected Def def(ParserRuleContext ctx, String kind) {
        Def def = new Def();
        def.defStart = ctx.getStart().getStartIndex();
        def.defEnd = ctx.getStop().getStopIndex();
        def.name = ctx.getText();
        def.file = this.file;
        def.kind = kind;
        return def;
    }

    protected Def def(Token token, String kind) {
        Def def = new Def();
        def.defStart = token.getStartIndex();
        def.defEnd = token.getStopIndex();
        def.name = token.getText();
        def.file = this.file;
        def.kind = kind;
        return def;
    }

    protected Ref ref(ParserRuleContext ctx) {
        Ref ref = new Ref();
        ref.start = ctx.getStart().getStartIndex();
        ref.end = ctx.getStop().getStopIndex();
        ref.file = this.file;
        return ref;
    }

    protected Ref ref(TerminalNode node) {
        Ref ref = new Ref();
        ref.start = node.getSymbol().getStartIndex();
        ref.end = node.getSymbol().getStopIndex();
        ref.file = this.file;
        return ref;
    }

    protected void emit(Def def) {
        try {
            graph.writer.writeDef(def);
        } catch (IOException e) {
            e.printStackTrace(); // TODO
        }
        // auto-adding self-references
        Ref ref = new Ref();
        ref.defKey = def.defKey;
        ref.def = true;
        ref.start = def.defStart;
        ref.end = def.defEnd;
        ref.file = def.file;
        emit(ref);
    }

    protected void emit(Ref ref) {
        try {
            graph.writer.writeRef(ref);
        } catch (IOException e) {
            e.printStackTrace(); // TODO
        }
    }

    protected void processMethodDeclaration(String className,
                                            ObjCParser.Method_declarationContext ctx,
                                            boolean isClassMethod) {
        Def methodDef;
        ObjCParser.Method_selectorContext methodSelectorContext = ctx.method_selector();
        ObjCParser.SelectorContext selectorContext = methodSelectorContext.selector();
        if (selectorContext != null) {
            methodDef = def(selectorContext, "METHOD");
        } else {
            methodDef = def(methodSelectorContext.keyword_declarator().get(0).selector(), "METHOD");
        }

        String key = className + '/' + getFuncName(methodSelectorContext);
        graph.functions.add(key);
        methodDef.defKey = new DefKey(null, key);
        emit(methodDef);

        Ref typeRef = ref(ctx.method_type().type_name());
        typeRef.defKey = new DefKey(null, ctx.method_type().type_name().getText());
        emit(typeRef);

        if (selectorContext == null) {
            // args
            boolean first = true;
            for (ObjCParser.Keyword_declaratorContext declaratorCtx : methodSelectorContext.keyword_declarator()) {
                ObjCParser.SelectorContext sContext = declaratorCtx.selector();
                if (sContext != null && sContext.IDENTIFIER() != null && !first) {
                    Def argDef = def(sContext, "VAR");
                    argDef.defKey = new DefKey(null, key + '/' + sContext.IDENTIFIER().getText());
                    emit(argDef);
                }
                List<ObjCParser.Method_typeContext> methodTypeContexts = declaratorCtx.method_type();
                if (methodTypeContexts != null) {
                    for (ObjCParser.Method_typeContext methodTypeContext : methodTypeContexts) {
                        ObjCParser.Type_nameContext typeNameContext = methodTypeContext.type_name();
                        Ref argTypeRef = ref(typeNameContext);
                        argTypeRef.defKey = new DefKey(null, typeNameContext.getText());
                        emit(argTypeRef);
                    }
                }
                first = false;
            }
        }

    }

    private String getFuncName(ObjCParser.Method_selectorContext methodSelectorContext) {
        StringBuilder ret = new StringBuilder();
        ObjCParser.SelectorContext selectorContext = methodSelectorContext.selector();
        if (selectorContext != null) {
            ret.append(selectorContext.getText()).append(':');
        } else {
            List<ObjCParser.Keyword_declaratorContext> keywordDeclaratorContexts = methodSelectorContext.
                    keyword_declarator();
            if (!keywordDeclaratorContexts.isEmpty()) {
                for (ObjCParser.Keyword_declaratorContext ctx : keywordDeclaratorContexts) {
                    ObjCParser.SelectorContext sc = ctx.selector();
                    if (sc != null) {
                        ret.append(sc.getText());
                    }
                    ret.append(':');
                }
            } else {
                ret.append(methodSelectorContext.getText()).append(':');
            }
        }
        return ret.toString();
    }

    private String getFuncName(ObjCParser.Message_selectorContext messageSelectorContext) {
        StringBuilder ret = new StringBuilder();
        ObjCParser.SelectorContext selectorContext = messageSelectorContext.selector();
        if (selectorContext != null) {
            ret.append(selectorContext.getText()).append(':');
        } else {
            List<ObjCParser.Keyword_argumentContext> keywordArgumentContexts = messageSelectorContext.keyword_argument();
            if (!keywordArgumentContexts.isEmpty()) {
                for (ObjCParser.Keyword_argumentContext ctx : keywordArgumentContexts) {
                    ObjCParser.SelectorContext sc = ctx.selector();
                    if (sc != null) {
                        ret.append(sc.getText());
                    }
                    ret.append(':');
                }
            } else {
                ret.append(messageSelectorContext.getText()).append(':');
            }
        }
        return ret.toString();
    }

    protected void processMethodDefinition(ObjCParser.Method_definitionContext methodDefinitionContext,
                                           boolean isClassMethod) {
        paramsVars.clear();
        blockCounter = 0;
        // TODO: implementation of parent interface
        ObjCParser.SelectorContext selectorContext = methodDefinitionContext.method_selector().selector();
        Ref methodRef;
        String defKey = currentClassName + '/' +
                getFuncName(methodDefinitionContext.method_selector());
        if (selectorContext == null) {
            List<ObjCParser.Keyword_declaratorContext> keywordDeclaratorContexts = methodDefinitionContext.
                    method_selector().keyword_declarator();
            if (!keywordDeclaratorContexts.isEmpty()) {
                methodRef = ref(keywordDeclaratorContexts.get(0).selector());
                // registering function arguments as variables
                for (ObjCParser.Keyword_declaratorContext keywordDeclaratorContext : methodDefinitionContext.
                        method_selector().keyword_declarator()) {
                    ObjCParser.Method_typeContext methodTypeContext = keywordDeclaratorContext.method_type(0);
                    String argTypeName;
                    if (methodTypeContext != null) {
                        ObjCParser.Type_nameContext typeNameContext = keywordDeclaratorContext.method_type(0).type_name();
                        Ref typeRef = ref(typeNameContext);
                        typeRef.defKey = new DefKey(null, typeNameContext.getText());
                        emit(typeRef);
                        argTypeName = typeNameContext.getText();
                    } else {
                        // example
                        // (void)animationWithSpriteFrames:animFrames delay:(float)delay...
                        argTypeName = "id";
                    }
                    paramsVars.put(keywordDeclaratorContext.getStop().getText(), argTypeName);
                    Def argDef = def(keywordDeclaratorContext.getStop(), "VAR");
                    argDef.defKey = new DefKey(null, defKey + '/' + keywordDeclaratorContext.getStop().getText());
                    emit(argDef);
                }
            } else {
                methodRef = ref(methodDefinitionContext.method_selector());
            }
        } else {
            methodRef = ref(selectorContext);
        }
        methodRef.defKey = new DefKey(null, defKey);
        emit(methodRef);
        ObjCParser.Type_nameContext typeNameContext = methodDefinitionContext.method_type().type_name();
        Ref typeRef = ref(typeNameContext);
        typeRef.defKey = new DefKey(null, typeNameContext.getText());
        emit(typeRef);
    }

    private ParserRuleContext ident(ObjCParser.DeclaratorContext context) {
        if (context == null) {
            return null;
        }
        ObjCParser.Direct_declaratorContext directDeclaratorContext = context.direct_declarator();
        ObjCParser.IdentifierContext identifierContext = directDeclaratorContext.identifier();
        if (identifierContext != null) {
            return identifierContext;
        }
        ObjCParser.DeclaratorContext declaratorContext = directDeclaratorContext.declarator();
        if (declaratorContext == null) {
            return null;
        }
        return ident(declaratorContext);
    }

    private String processTypeSpecifier(ObjCParser.Type_specifierContext ctx) {
        ObjCParser.Protocol_reference_listContext protocolReferenceListContext = ctx.
                protocol_reference_list();
        if (protocolReferenceListContext != null) {
            for (ObjCParser.Protocol_nameContext protocolNameContext : protocolReferenceListContext.
                    protocol_list().protocol_name()) {
                Ref typeRef = ref(protocolNameContext);
                typeRef.defKey = new DefKey(null, protocolNameContext.getText());
                emit(typeRef);
            }
        }
        ObjCParser.Class_nameContext classNameContext = ctx.class_name();
        if (classNameContext != null && !isReservedSpecifier(classNameContext.getText())) {
            Ref typeRef = ref(classNameContext);
            typeRef.defKey = new DefKey(null, classNameContext.getText());
            emit(typeRef);
            return classNameContext.getText();
        }
        if (ctx.IDENTIFIER() != null && !isReservedSpecifier(ctx.IDENTIFIER().getText())) {
            Ref typeRef = ref(ctx.IDENTIFIER());
            typeRef.defKey = new DefKey(null, ctx.IDENTIFIER().getText());
            emit(typeRef);
            return ctx.IDENTIFIER().getText();
        }

        String maybePredefined = ctx.getText();
        if (ArrayUtils.indexOf(PREDEFINED_TYPES, maybePredefined) >= 0) {
            Ref typeRef = ref(ctx);
            typeRef.defKey = new DefKey(null, maybePredefined);
            emit(typeRef);
            return maybePredefined;
        }
        return null;
    }

    private boolean isReservedSpecifier(String text) {
        return text.equals("inline") || text.equals("static");
    }

    private void processInstanceVariables(Map<String, String> currentClassVars,
                                          ObjCParser.Instance_variablesContext instanceVariablesContext) {
        if (instanceVariablesContext != null) {
            for (ObjCParser.Struct_declarationContext structDeclarationContext : instanceVariablesContext.struct_declaration()) {

                String typeName = null;
                // type refs
                for (ObjCParser.Type_specifierContext typeSpecifierContext : structDeclarationContext.
                        specifier_qualifier_list().type_specifier()) {
                    String type = processTypeSpecifier(typeSpecifierContext);
                    if (type != null) {
                        typeName = type;
                    }
                }

                // variable defs
                for (ObjCParser.Struct_declaratorContext structDeclaratorContext : structDeclarationContext.
                        struct_declarator_list().struct_declarator()) {
                    Def propertyDef = def(structDeclaratorContext.declarator().direct_declarator(), "VAR");
                    propertyDef.defKey = new DefKey(null, currentClassName + '/' + propertyDef.name);
                    emit(propertyDef);
                    currentClassVars.put(propertyDef.name, typeName);
                }

            }
        }
    }

    private void processProtocolReferences(ObjCParser.Protocol_reference_listContext protocolReferenceListContext) {
        if (protocolReferenceListContext != null) {
            ObjCParser.Protocol_listContext protocolListContext = protocolReferenceListContext.protocol_list();
            if (protocolListContext != null) {
                List<ObjCParser.Protocol_nameContext> protocolNameContexts = protocolListContext.protocol_name();
                if (protocolNameContexts != null) {
                    for (ObjCParser.Protocol_nameContext protocolNameContext : protocolNameContexts) {
                        Ref protocolRef = ref(protocolNameContext);
                        protocolRef.defKey = new DefKey(null, protocolNameContext.getText());
                        emit(protocolRef);
                    }
                }
            }
        }
    }

    private void processDeclarationList(ObjCParser.Interface_declaration_listContext interfaceDeclarationListContext) {
        if (interfaceDeclarationListContext != null) {
            List<ObjCParser.Class_method_declarationContext> classMethodDeclarationContexts =
                    interfaceDeclarationListContext.class_method_declaration();
            if (classMethodDeclarationContexts != null) {
                for (ObjCParser.Class_method_declarationContext classMethodDeclarationContext : classMethodDeclarationContexts) {
                    processMethodDeclaration(currentClassName, classMethodDeclarationContext.method_declaration(), true);
                }
            }
            List<ObjCParser.Instance_method_declarationContext> instanceMethodDeclarationContexts =
                    interfaceDeclarationListContext.instance_method_declaration();
            if (instanceMethodDeclarationContexts != null) {
                for (ObjCParser.Instance_method_declarationContext instanceMethodDeclarationContext : instanceMethodDeclarationContexts) {
                    processMethodDeclaration(currentClassName, instanceMethodDeclarationContext.method_declaration(), false);
                }
            }
        }
    }

    private Var getLocalVariable(String variable) {
        for (int i = localVars.size() - 1; i >= 0; i--) {
            Var var = localVars.get(i).get(variable);
            if (var != null) {
                return var;
            }
        }
        return null;
    }

    private String currentDefKey(String ident) {
        StringBuilder ret = new StringBuilder();
        if (currentClassName != null) {
            ret.append(currentClassName).append('/');
        }
        if (currentMethodName != null) {
            ret.append(currentMethodName).append('/');
        }
        ret.append(ident);
        return ret.toString();
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer,
                            Object offendingSymbol,
                            int line,
                            int charPositionInLine,
                            String msg,
                            RecognitionException e) {
        LOGGER.warn("{} at {}:{}: {}", file, line, charPositionInLine, msg);
    }

    @Override
    public void reportAmbiguity(Parser parser,
                                DFA dfa,
                                int i,
                                int i1,
                                boolean b,
                                BitSet bitSet,
                                ATNConfigSet atnConfigSet) {

    }

    @Override
    public void reportAttemptingFullContext(Parser parser,
                                            DFA dfa,
                                            int i,
                                            int i1,
                                            BitSet bitSet,
                                            ATNConfigSet atnConfigSet) {

    }

    @Override
    public void reportContextSensitivity(Parser parser, DFA dfa, int i, int i1, int i2, ATNConfigSet atnConfigSet) {
    }

    private class Var {
        String type;
        String defKey;

        Var(String name, String type) {
            this.type = type;
            this.defKey = currentDefKey(name);
            if (blockCounter > 0) {
                this.defKey += "$" + blockCounter;
            }
        }
    }
}
package no.ssb.vtl.tools.rest.controllers;

/*-
 * ========================LICENSE_START=================================
 * Java VTL
 * %%
 * Copyright (C) 2016 - 2017 Hadrien Kohl
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */

import com.google.common.collect.Lists;
import no.ssb.vtl.model.DataStructure;
import no.ssb.vtl.model.Dataset;
import no.ssb.vtl.parser.VTLLexer;
import no.ssb.vtl.parser.VTLParser;
import no.ssb.vtl.script.VTLScriptEngine;
import no.ssb.vtl.script.error.VTLCompileException;
import no.ssb.vtl.script.error.VTLScriptException;
import no.ssb.vtl.tools.rest.representations.BindingsRepresentation;
import no.ssb.vtl.tools.rest.representations.SyntaxErrorRepresentation;
import no.ssb.vtl.tools.rest.representations.ThrowableRepresentation;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.tool.GrammarParserInterpreter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.script.Bindings;
import javax.script.ScriptException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A validator service that returns syntax errors for expressions.
 */
@RequestMapping(
        produces = MediaType.APPLICATION_JSON_UTF8_VALUE,
        consumes = MediaType.ALL_VALUE
)
@RestController
public class ValidatorController {

    private final VTLScriptEngine engine;

    @Autowired
    public ValidatorController(VTLScriptEngine engine) {
        this.engine = checkNotNull(engine);
    }

    private static SyntaxErrorRepresentation convertToSyntaxError(VTLScriptException exception) {
        return new SyntaxErrorRepresentation(
                exception.getStartLine(),
                exception.getStopLine(),
                exception.getStartColumn(),
                exception.getStopColumn(),
                exception.getMessage(),
                null
        );
    }

    @ExceptionHandler
    @ResponseStatus()
    public Object handleError(Throwable t) {
        return new ThrowableRepresentation(t);
    }

    /**
     * Expose bindings after executing the expression
     */
    @RequestMapping(
            path = "/bindings",
            method = RequestMethod.POST,
            consumes = {
                    MediaType.ALL_VALUE,
                    MediaType.TEXT_PLAIN_VALUE
            }
    )
    @Cacheable(cacheNames = "bindings", key = "@hasher.apply(#expression)")
    public List<BindingsRepresentation> variables(
            @RequestBody(required = false) String expression
    ) {
        Bindings bindings = engine.createBindings();
        if (expression != null && !"".equals(expression)) {
            try {
                engine.eval(expression, bindings);
            } catch (ScriptException se) {
                // Ignore.
            }
        }
        List<BindingsRepresentation> result = Lists.newArrayList();
        for (String name : bindings.keySet()) {
            BindingsRepresentation representation = new BindingsRepresentation();
            representation.setName(name);
            Object object = bindings.get(name);
            if (object instanceof Dataset) {
                ArrayList<BindingsRepresentation> children = Lists.newArrayList();
                DataStructure structure = ((Dataset) object).getDataStructure();
                for (String column : structure.keySet()) {
                    BindingsRepresentation columnRepresentation = new BindingsRepresentation();
                    columnRepresentation.setName(column);
                    columnRepresentation.setRole(structure.get(column).getRole());
                    columnRepresentation.setType(structure.get(column).getType());
                    children.add(columnRepresentation);
                }
                representation.setChildren(children);
            } else {
                Optional<Class<?>> clazz = Optional.ofNullable(object).map(Object::getClass);
                representation.setType(clazz.orElse(Object.class));
            }
            result.add(representation);
        }
        return result;
    }

    /**
     * Validate a VTL Expression
     */
    @RequestMapping(
            path = "/validate",
            method = RequestMethod.POST,
            consumes = {
                    MediaType.ALL_VALUE,
                    MediaType.TEXT_PLAIN_VALUE
            }
    )
    @Cacheable(cacheNames = "validations", key = "@hasher.apply(#expression)")
    public List<SyntaxErrorRepresentation> validate(
            @RequestBody(required = false) String expression
    ) {
        if (expression != null && !"".equals(expression)) {

            // OSB: ANTLRInputStream is deprecated but still contains a bug
            VTLLexer lexer = new VTLLexer(new ANTLRInputStream(expression));
            VTLParser parser = new VTLParser(new BufferedTokenStream(lexer));

            lexer.removeErrorListeners();
            parser.removeErrorListeners();

            parser.setErrorHandler(new GrammarParserInterpreter.BailButConsumeErrorStrategy());

            ErrorListener errorListener = new ErrorListener();
            lexer.addErrorListener(errorListener);
            parser.addErrorListener(errorListener);

            parser.start();

            return errorListener.getSyntaxErrors();
        }
        return Collections.emptyList();
    }

    /**
     * Check a VTL Expression
     */
    @RequestMapping(
            path = "/check",
            method = RequestMethod.POST,
            consumes = {
                    MediaType.ALL_VALUE,
                    MediaType.TEXT_PLAIN_VALUE
            }
    )
    @Cacheable(cacheNames = "checks", key = "@hasher.apply(#expression)")
    public List<SyntaxErrorRepresentation> check(
            @RequestBody(required = false) String expression
    ) throws ScriptException {
        if (expression != null && !"".equals(expression)) {
            ArrayList<SyntaxErrorRepresentation> validate = Lists.newArrayList(validate(expression));
            if (!validate.isEmpty()) {
                return validate;
            }

            try {
                Bindings emptyBindings = engine.createBindings();
                engine.eval(expression, emptyBindings);
            } catch (VTLCompileException vce) {
                return vce.getErrors()
                        .stream()
                        .map(ValidatorController::convertToSyntaxError)
                        .collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    /**
     * @return a collection of all keywords / reserved words
     */
    @RequestMapping(
            path = "/keywords",
            method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_UTF8_VALUE
    )
    public Map<String, Set<String>> getKeywords() {
        return engine.getVTLKeywords();
    }

    /**
     * An {@link org.antlr.v4.runtime.ANTLRErrorListener} implementation that collects
     * errors and create {@link SyntaxErrorRepresentation}
     */
    public static class ErrorListener extends BaseErrorListener {

        private List<SyntaxErrorRepresentation> syntaxErrors = Lists.newArrayList();

        public List<SyntaxErrorRepresentation> getSyntaxErrors() {
            return syntaxErrors;
        }

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
            int startLine = line, stopLine = line;
            int startColumn = charPositionInLine, stopColumn = charPositionInLine;
            if (offendingSymbol instanceof Token) {
                Token symbol = (Token) offendingSymbol;
                int start = symbol.getStartIndex();
                int stop = symbol.getStopIndex();
                if (start >= 0 && stop >= 0) {
                    stopColumn = startColumn + (stop - start) + 1;
                }
            }
            syntaxErrors.add(new SyntaxErrorRepresentation(startLine, stopLine, startColumn, stopColumn, msg, e));
        }
    }
}

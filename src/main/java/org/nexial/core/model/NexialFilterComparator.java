/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.nexial.core.model;

import java.util.*;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.nexial.commons.utils.TextUtils;

import static org.nexial.core.NexialConst.FlowControls.IS_CLOSE_TAG;
import static org.nexial.core.NexialConst.FlowControls.IS_OPEN_TAG;
import static org.nexial.core.model.NexialFilter.ITEM_SEP;
import static org.nexial.core.model.NexialFilterComparator.InternalMappings.COMPARATOR_ORDER;

/** all possible ways to evaluate a filter */
public enum NexialFilterComparator {
    GreaterOrEqual(" >= ", 1, Number.class, 1.0),
    GreaterOrEqual_2(">=", 1, Number.class, 1.1),
    Greater(" > ", 1, Number.class, 1.2),
    Greater_2(">", 1, Number.class, 1.3),
    LesserOrEqual(" <= ", 1, Number.class, 1.4),
    LesserOrEqual_2("<=", 1, Number.class, 1.5),
    Lesser(" < ", 1, Number.class, 1.6),
    Lesser_2("<", 1, Number.class, 1.7),
    NotEqual(" != ", 1, String.class, 1.8),
    NotEqual_2("!=", 1, String.class, 1.9),
    Equal(" = ", 1, String.class, 1.91),
    Equal_2("=", 1, String.class, 1.92),

    NotIn(" not in ", -1, String.class, 2.0),
    In(" in ", -1, String.class, 2.1),

    // all the "is not" must be listed before `IsNote` comparator to avoid parsing error
    NotReadableFile(" is not readable-file", 0, Boolean.class, 2.2),
    NotReadablePath(" is not readable-path", 0, Boolean.class, 2.21),
    NotEmptyPath(" is not empty-path", 0, Boolean.class, 2.22),
    IsNotEmpty(" is not empty", 0, null, 2.23),
    IsNot(" is not ", -1, String.class, 2.24),

    // all the "is" must be listed before `Is` comparator to avoid parsing error,
    IsDefined(" is defined", 0, null, 2.3),
    IsUndefined(" is undefined", 0, null, 2.31),
    ReadableFile(" is readable-file", 0, Boolean.class, 2.32),
    ReadablePath(" is readable-path", 0, Boolean.class, 2.33),
    EmptyPath(" is empty-path", 0, Boolean.class, 2.34),
    IsEmpty(" is empty", 0, null, 2.35),
    Is(" is ", -1, String.class, 2.36),

    NotContain(" not contain ", -1, String.class, 3.1),
    NotStartsWith(" not start with ", 1, String.class, 3.2),
    NotEndsWith(" not end with ", 1, String.class, 3.3),

    ContainFilePattern(" contain file pattern ", 1, String.class, 3.34),
    ContainFile(" contain file ", 1, String.class, 3.35),

    Between(" between ", 2, Number.class, 3.4),
    Contain(" contain ", 1, String.class, 3.5),
    StartsWith(" start with ", 1, String.class, 3.6),
    EndsWith(" end with ", 1, String.class, 3.7),
    Match(" match ", 1, String.class, 3.8),
    HasLengthOf(" has length of ", 1, Number.class, 3.9),

    ReadableFileWithSize(" has file-size ", 1, Number.class, 4.0),
    HasFileContentPattern(" has file content pattern ", 1, Number.class, 4.1),
    HasFileContent(" has file content ", 1, Number.class, 4.2),
    LastModifiedGreater(" has lastmod > ", 1, Number.class, 4.3),
    LastModifiedLesser(" has lastmod < ", 1, Number.class, 4.4),
    LastModifiedEqual(" has lastmod = ", 1, Number.class, 4.5),

    TrueOrFalse(null, 0, Boolean.class, 5.0),

    Any(null, 0, null, 10);

    private static final List<String> REQUIRED_WRAP_SPACE = Arrays.asList(">", ">=", "<", "<=", "=", "!=");
    private static final String REGEX_CONTROLS = "(\".+?\"|.+?)?";
    private static final String REGEX_FILTER = initRegexFilter();

    private final String symbol;
    private final int expectedControlSize;
    private final Class expectedType;
    private final double order;

    interface InternalMappings {
        Map<String, NexialFilterComparator> COMPARATOR_MAP = new HashMap<>();
        Map<Double, NexialFilterComparator> COMPARATOR_ORDER = new TreeMap<>();
    }

    NexialFilterComparator(String symbol, int expectedControlSize, Class expectedType, double order) {
        this.symbol = symbol;
        this.expectedControlSize = expectedControlSize;
        this.expectedType = expectedType;
        this.order = order;
        if (symbol != null) {
            InternalMappings.COMPARATOR_MAP.put(symbol, this);
            COMPARATOR_ORDER.put(order, this);
        }
    }

    public String getSymbol() { return symbol; }

    public int getExpectedControlSize() { return expectedControlSize; }

    public Class getExpectedType() { return expectedType; }

    public double getOrder() { return order; }

    public static NexialFilterComparator findComparator(String symbol) {
        return symbol == null ? Any : InternalMappings.COMPARATOR_MAP.get(symbol);
    }

    public static String getRegexFilter() { return REGEX_FILTER; }

    public Pair<String, List<String>> formatControlValues(String controlText) throws IllegalArgumentException {
        String errPrefix = "Invalid -  '" + this + "' on '" + controlText + "': ";

        int controlSize = this.getExpectedControlSize();

        if (StringUtils.isBlank(controlText)) {
            if (controlSize != 0) {throw new IllegalArgumentException(errPrefix + "empty/blank controls NOT expected");}
            return new ImmutablePair<>("", new ArrayList<>());
        }

        List<String> controlList = new ArrayList<>();

        // could be 1
        if (controlSize == 1) {
            // we should check for type compatibility at this time since data variable might be in reference
            // controlText = checkTypeCompatibility(NexialFilter.normalizeCondition(controlText));
            controlText = NexialFilter.normalizeCondition(controlText);
            controlList.add(controlText);
            return new ImmutablePair<>(controlText, controlList);
        }

        // could be 2 or -1 (any)
        controlText = StringUtils.trim(controlText);
        if (!TextUtils.isBetween(controlText, IS_OPEN_TAG, IS_CLOSE_TAG)) {
            controlText = IS_OPEN_TAG + NexialFilter.normalizeCondition(controlText) + IS_CLOSE_TAG;
        }

        // parse into individual control
        String[] controls =
            StringUtils.split(StringUtils.substringBetween(controlText, IS_OPEN_TAG, IS_CLOSE_TAG), ITEM_SEP);
        if (ArrayUtils.isEmpty(controls)) {
            throw new IllegalArgumentException(errPrefix + "expects " + controlSize + " control(s)");
        }

        int parsedCount = controls.length;
        if (controlSize == 2 && parsedCount != 2) {
            throw new IllegalArgumentException(errPrefix + "expects " + controlSize + " control(s)");
        }
        // if controlSize == -1 then 1 or more is expected (1 is ok)

        StringBuilder buffer = new StringBuilder(IS_OPEN_TAG);
        Arrays.stream(controls).forEach(control -> {
            // we should check for type compatibility at this time since data variable might be in reference
            // String formatted = StringUtils.equals(StringUtils.trim(control), "\"\"") ?
            //                    "\"\"" : checkTypeCompatibility(NexialFilter.normalizeCondition(control));
            String formatted = StringUtils.equals(StringUtils.trim(control), "\"\"") ?
                               "\"\"" : NexialFilter.normalizeCondition(control);

            // reformat control text to standard form
            buffer.append(formatted).append(ITEM_SEP);
            controlList.add(formatted);
        });

        return new ImmutablePair<>(StringUtils.removeEnd(buffer.toString(), ITEM_SEP) + IS_CLOSE_TAG, controlList);
    }

    @Override
    public String toString() {
        return REQUIRED_WRAP_SPACE.contains(symbol) ? StringUtils.wrapIfMissing(symbol, " ") : symbol;
    }

    private static String initRegexFilter() {
        try {
            Class.forName(NexialFilterComparator.class.getName());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Unable to load NexialFilterComparator!", e);
        }

        Map<Object, Object> replacements = new HashMap<>();
        replacements.put("!", "\\!");
        replacements.put("=", "\\=");
        replacements.put("<", "\\<");
        replacements.put(">", "\\>");

        StringBuilder regexOperator = new StringBuilder("(");
        COMPARATOR_ORDER.values()
                        .forEach(operator -> regexOperator.append(TextUtils.replace(operator.getSymbol(), replacements))
                                                          .append("|"));

        String regexOps = StringUtils.removeEnd(regexOperator.toString(), "|") + ")";
        // hack: order is important. "is" must come after "is not"
        regexOps = StringUtils.replace(regexOps,
                                       Is.getSymbol() + "|" + IsNot.getSymbol(),
                                       IsNot.getSymbol() + "|" + Is.getSymbol());

        // narrative:
        // - start with any spaces
        // - capture "subject"
        // - at least 1 space
        // - capure "operator"
        // - at least 1 space
        // - capture "controls"
        // - end with any space
        return "^\\s*" + REGEX_CONTROLS + "\\s*" + regexOps + "\\s*" + REGEX_CONTROLS + "\\s*$";
    }

    private String checkTypeCompatibility(String control) {
        if (expectedType == Number.class) {
            if (!NexialFilter.canBeNumber(control)) {
                throw new IllegalArgumentException("Invalid -  '" + this + "' on '" + control + "': EXPECTS numeric");
            }
        }

        return control;
    }
}

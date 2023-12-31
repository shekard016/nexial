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

package org.nexial.core.tms.model;

/**
 * Enum to store the various keywords used in BDD
 */
public enum BDDKeywords {
    FEATURE("FEATURE"),
    RULE("RULE"),
    GIVEN("GIVEN"),
    SCENARIO("SCENARIO"),
    EXAMPLE("EXAMPLE"),
    WHEN("WHEN"),
    THEN("THEN"),
    AND("AND"),
    BUT("BUT"),
    BACKGROUND("BACKGROUND"),
    SCENARIO_OUTLINE("SCENARIO OUTLINE");

    private final String keyword;

    BDDKeywords(String keyword) { this.keyword = keyword; }

    public String getKeyword() { return keyword; }
}

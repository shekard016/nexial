/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.nexial.core.plugins.desktop;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.collections4.map.ListOrderedMap;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.nexial.commons.utils.TextUtils;
import org.nexial.core.ExecutionThread;
import org.nexial.core.NexialConst.PolyMatcher;
import org.nexial.core.model.ExecutionContext;
import org.nexial.core.model.StepResult;
import org.nexial.core.plugins.desktop.ig.IgExplorerBar;
import org.nexial.core.utils.ConsoleUtils;
import org.nexial.core.utils.JsonUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.nexial.core.NexialConst.Desktop.CLEAR_TABLE_CELL_BEFORE_EDIT;
import static org.nexial.core.NexialConst.NL;
import static org.nexial.core.SystemVariables.getDefaultBool;
import static org.nexial.core.plugins.desktop.DesktopConst.*;
import static org.nexial.core.plugins.desktop.DesktopUtils.*;
import static org.nexial.core.plugins.desktop.ElementType.*;
import static org.nexial.core.utils.CheckUtils.requiresNotNull;
import static org.openqa.selenium.Keys.ESCAPE;

/**
 * capture both behavior and data of a {@link ElementType#Table} element (the corresponding @ControlType is
 * {@link ElementType#TABLE}).
 * <p>
 * Instance of this element may be stored in session ({@link DesktopSession}) so that its content and state can be
 * retained for the span of an execution cycle.
 * <p>
 * As of v3.7, we will support HierTable (aka ControlType.Tree) as well
 */
public class DesktopTable extends DesktopElement {
    protected List<String> headers;
    protected Map<String, String> cellTypes;
    protected int columnCount = UNDEFINED;
    protected int headerHeight = TABLE_HEADER_HEIGHT;
    protected int clickOffsetX = TABLE_ROW_X_OFFSET;
    // in case some table column headers contains multiple/repeating spaces, setting this property to true means Nexial
    // would normalize the repeating spaces in column header during XPATH generation
    protected boolean normalizeSpace;
    protected boolean isTreeView;

    public static class TableMetaData {
        protected List<String> headers;
        protected Map<String, String> cellTypes;
        protected int columnCount;
        protected boolean normalizeSpace;
        protected transient int rowCount;

        public List<String> getHeaders() { return headers; }

        public void setHeaders(List<String> headers) { this.headers = headers; }

        public int getColumnCount() { return columnCount; }

        public void setColumnCount(int columnCount) { this.columnCount = columnCount; }

        public int getRowCount() { return rowCount; }

        public void setRowCount(int rowCount) { this.rowCount = rowCount; }

        public Map<String, String> getCellTypes() { return cellTypes; }

        public void setCellTypes(Map<String, String> cellTypes) { this.cellTypes = cellTypes; }

        public boolean isNormalizeSpace() { return normalizeSpace; }

        public void setNormalizeSpace(boolean normalizeSpace) { this.normalizeSpace = normalizeSpace; }

        @Override
        public String toString() {
            return "headers='" + headers + "', \n" +
                   "cellTypes='" + cellTypes + "', \n" +
                   "columnCount='" + columnCount + "', \n" +
                   "rowCount='" + rowCount + "', \n" +
                   "normalizeSpace='" + normalizeSpace + "'";
        }
    }

    protected DesktopTable() { }

    /** As of v3.7, we will support HierTable (aka ControlType.Tree) as well */
    public static DesktopTable toInstance(DesktopElement component) {
        DesktopTable instance = new DesktopTable();
        copyTo(component, instance);
        if (component.getElementType() == HierTable) { instance.isTreeView = true; }

        if (MapUtils.isNotEmpty(instance.extra)) {
            // needed for multi-line headers
            if (instance.extra.containsKey("normalizeSpace")) {
                instance.normalizeSpace = BooleanUtils.toBoolean(instance.extra.get("normalizeSpace"));
            }

            // header list specified manually to avoid time needed to discover this dynamically
            if (instance.extra.containsKey("headers")) {
                instance.headers = TextUtils.toList(instance.extra.get("headers"), ",", true);
                if (instance.normalizeSpace && CollectionUtils.isNotEmpty(instance.headers)) {
                    List<String> normalized = new ArrayList<>();
                    instance.headers.forEach(header -> normalized.add(TextUtils.xpathNormalize(header)));
                    instance.headers = normalized;
                }
                instance.columnCount = CollectionUtils.size(instance.headers);

                if (instance.extra.containsKey("headerHeight")) {
                    instance.headerHeight = NumberUtils.toInt(instance.extra.get("headerHeight"));
                }
                if (instance.extra.containsKey("clickOffsetX")) {
                    instance.clickOffsetX = NumberUtils.toInt(instance.extra.get("clickOffsetX"));
                }
            }

            // specify the cells that require different treatment (ie FormattedTextbox)
            if (instance.extra.containsKey("cellTypes")) {
                instance.cellTypes = TextUtils.toMap(StringUtils.trim(instance.extra.get("cellTypes")), ",", "=");
            }
        }
        return instance;
    }

    public List<String> getHeaders() { return headers; }

    public int getColumnCount() { return columnCount; }

    @Override
    public String toString() {
        return "columnCount='" + columnCount + "', \n" +
               "headers='" + headers + "', \n" +
               "cellTypes='" + cellTypes + "', \n" +
               "normalizeSpace='" + normalizeSpace + "'";
    }

    /**
     * scan table structure, optionally forcing the rescanning of row information so that we get the latest row count.
     * Consequently  any previously harvested rows will be cleared out as well.
     */
    public TableMetaData scanStructure() {
        sanityCheck();

        // short-circuit: if header information is provided via extra, then we don't need to do the scanning again
        if (CollectionUtils.isEmpty(headers) || CollectionUtils.size(headers) != columnCount) {
            // collect headers
            String xpath = null;
            if (isTreeView) {
                // first try with a data row since we'd get more accurate headers (correct order)
                xpath = "*[@ControlType='ControlType.DataItem'][1]/*";
                ConsoleUtils.log("scanning for table structure via " + xpath);
                List<WebElement> columns = element.findElements(By.xpath(xpath));
                if (CollectionUtils.isEmpty(columns)) {
                    ConsoleUtils.log("No data found; re-scanning for table structure via " + xpath);
                    xpath = LOCATOR_HIER_HEADER_COLUMNS;
                    columns = element.findElements(By.xpath(xpath));
                }

                headers = columns.stream().map(elem -> elem.getAttribute(ATTR_NAME)).collect(Collectors.toList());
                columnCount = headers.size();

            } else {
                String row = getFirstAvailableRowName();
                if (StringUtils.isNotEmpty(row)) {
                    xpath = "*[@Name='" + row + "']/*[@Name!='Column Headers']";
                    ConsoleUtils.log("scanning for table structure via " + xpath);
                    headers = element.findElements(By.xpath(xpath)).stream()
                                     .map(elem -> elem.getAttribute(ATTR_NAME))
                                     .collect(Collectors.toList());
                    columnCount = headers.size();
                }
            }
        }

        TableMetaData metaData = new TableMetaData();
        metaData.setHeaders(headers);
        metaData.setRowCount(getTableRowCount());
        metaData.setCellTypes(cellTypes);
        metaData.setNormalizeSpace(normalizeSpace);
        metaData.setColumnCount(columnCount);
        return metaData;
    }

    @Nonnull
    private String getFirstAvailableRowName() {
        return element.findElements(By.xpath("*")).stream()
                      .filter(elem -> StringUtils.containsIgnoreCase(elem.getAttribute("Name"), "row"))
                      .findFirst()
                      .map(elem -> elem.getAttribute("Name")).orElse("");
    }

    @Nonnull
    private String getLastDataRowName() {
        return element.findElements(By.xpath("*")).stream()
                      .filter(elem -> StringUtils.contains(elem.getAttribute("Name"), "row "))
                      .map(elem -> elem.getAttribute("Name"))
                      .max(Comparator.naturalOrder()).orElse("");
    }

    @Nonnull
    private String getMatchingRowName(String endWith) {
        return element.findElements(By.xpath("*")).stream()
                      .filter(elem -> StringUtils.endsWith(elem.getAttribute("Name"), endWith))
                      .findFirst()
                      .map(elem -> elem.getAttribute("Name")).orElse("");
    }

    public List<List<String>> scanAllRows() {
        TableData tableData = fetchAll();
        return tableData.getRows();
    }

    /** As of v3.7, we will support HierTable (aka ControlType.Tree) and converting to CSV structure (text) */
    public void clickRow(int row) {
        sanityCheck();

        if (row < 0) { throw new IllegalArgumentException("row (zero-based) must be 0 or greater"); }

        String msgPrefix = "Table '" + name + "' ";
        int rowCount = getTableRowCount();
        if (rowCount < row + 1) {
            throw new IllegalArgumentException(msgPrefix + "contains less than " + (rowCount + 1) + " row(s); " +
                                               "cannot click row '" + row + "'");
        }

        if (rowCount == 0) {
            clickOffset(getElement(), clickOffsetX, (headerHeight + (TABLE_ROW_HEIGHT * row) + (TABLE_ROW_HEIGHT / 2)));
        } else {
            String xpath = isTreeView ? LOCATOR_HIER_FIRST_CELL : LOCATOR_FIRST_CELL;
            xpath = StringUtils.replace(xpath, "{row}", (row + 1) + "");
            clickElement(xpath);
        }
    }

    /** As of v3.7, we will support HierTable (aka ControlType.Tree) and converting to CSV structure (text) */
    public boolean clickCell(int row, String column) {
        sanityCheck(row, column);

        if (row > getTableRowCount()) {
            // perhaps user wants to click on a new row.. let's click new row by offset and then check if the new row is created
            clickOffset(element, clickOffsetX, (headerHeight + (TABLE_ROW_HEIGHT * row) + (TABLE_ROW_HEIGHT / 2)));
            // todo: need to figure out XPATH for HierTable
            String xpath = StringUtils.replace(LOCATOR_NEW_ROW_CELL, "{column}", column);
            try {
                return element.findElement(By.xpath(xpath)) != null;
            } catch (NoSuchElementException e) {
                return false;
            }
        }

        String xpath = isTreeView ? LOCATOR_HIER_CELL : LOCATOR_CELL;
        xpath = StringUtils.replace(xpath, "{row}", (row + 1) + "");
        xpath = StringUtils.replace(xpath, "{column}", column);
        return clickElement(xpath);
    }

    public int findColumnIndex(String column) { return headers.indexOf(column); }

    public boolean containsColumnHeader(String header) {
        return headers != null && headers.contains(treatColumnHeader(header));
    }

    /** As of v3.7, we will support HierTable (aka ControlType.Tree) as well */
    public TableData fetch(int begin, int end) {
        sanityCheck();
        Instant startTime = Instant.now();
        if (isTreeView) {
            String index = begin == end ?
                           "position()=" + (begin + 1) :
                           "position()>=" + (begin + 1) + " and position()<=" + (end + 1);
            String xpath = "*[@ControlType='ControlType.DataItem'][" + index + "]/*";
            List<WebElement> rowData = element.findElements(By.xpath(xpath));
            return TableData.fromTreeViewRows(headers, rowData, Duration.between(startTime, Instant.now()));
        } else {
            String object = (String) driver.executeScript(SCRIPT_DATAGRID_FETCH, element, begin, end);
            return new TableData(object, Duration.between(startTime, Instant.now()));
        }
    }

    /** As of v3.7, we will support HierTable (aka ControlType.Tree) as well */
    public TableData fetchAll() {
        sanityCheck();
        Instant startTime = Instant.now();
        if (isTreeView) {
            List<WebElement> rowData = element.findElements(By.xpath(LOCATOR_HIER_TABLE_ROWS + "/*"));
            return TableData.fromTreeViewRows(headers, rowData, Duration.between(startTime, Instant.now()));
        } else {
            String object = (String) driver.executeScript(SCRIPT_DATAGRID_FETCH_ALL, element);
            return new TableData(object, Duration.between(startTime, Instant.now()));
        }
    }

    /** As of v3.7, we will support HierTable (aka ControlType.Tree) as well */
    public int getTableRowCount() {
        if (isTreeView) {
            String xpath = StringUtils.appendIfMissing(getXpath(), "/") + LOCATOR_HIER_TABLE_ROWS;
            return CollectionUtils.size(driver.findElements(By.xpath(xpath)));
        }

        Object object = driver.executeScript(SCRIPT_DATAGRID_ROW_COUNT, element);
        if (object == null) { return collectRowCountByXpath(); }

        JSONObject jsonObject = JsonUtils.toJSONObject(object.toString());
        try {
            if (jsonObject == null || jsonObject.has("error")) {
                ConsoleUtils.log("error fetching row count by driver. Try to get row count by xpath");
                // get the rowCount using xpath
                return collectRowCountByXpath();
            }
            return jsonObject.getInt("rows");
        } catch (JSONException e) {
            // get the rowCount using xpath
            ConsoleUtils.log("error fetching row count by driver. Try to get row count by xpath");
            return collectRowCountByXpath();
        }
    }

    /** As of v3.7, we will support HierTable (aka ControlType.Tree) as well */
    public DesktopTableRow fetchOrCreateRow(int row) {
        sanityCheck(row, null);
        ExecutionContext context = ExecutionThread.get();

        List<WebElement> dataElements =
            element.findElements(By.xpath(isTreeView ? LOCATOR_HIER_TABLE_ROWS : LOCATOR_TABLE_DATA));
        if (CollectionUtils.isEmpty(dataElements)) { ConsoleUtils.log("No rows found for data grid " + element); }

        if (isTreeView && dataElements.size() == 1) {
            // test for initial empty row
            String rowIndex = dataElements.get(0).getAttribute("AutomationId");
            if (StringUtils.contains(rowIndex, "-1")) {
                // only 1 row and its id is -1 -- this is the initial empty row; meaning no rows yet created for this table
                dataElements.clear();
            }
        }

        List<WebElement> rows = new ArrayList<>();
        //todo: short circuit this: fetch only requested row
        dataElements.forEach(elem -> { if (elem != null) { rows.add(elem); }});

        int rowCount = CollectionUtils.size(rows);
        boolean newRow = false;
        ConsoleUtils.log("current row count: " + rowCount);
        if (row >= rowCount) {
            row = rowCount;
            newRow = true;
        }

        String msgPrefix = "Table '" + label + "' Row '" + row + "' ";

        // find through existing column for the ones that would match specified criteria
        List<WebElement> columns;
        if (newRow) {
            List<WebElement> matches;
            if (!isTreeView) {
                // using offsets, only when to click on the first row and it is new row.
                if (row == 0) {
                    if (clickBeforeEdit()) {
                        clickOffset(element, clickOffsetX, (headerHeight + (TABLE_ROW_HEIGHT / 2)));
                        try { Thread.sleep(2000);} catch (InterruptedException e) {}
                    }
                    matches = element.findElements(By.xpath(LOCATOR_NEW_ROW));
                    if (CollectionUtils.isEmpty(matches)) {
                        // try clicking on first row
                        WebElement firstRow =
                            element.findElements(By.xpath("*")).stream()
                                   .filter(elem -> StringUtils.containsIgnoreCase(elem.getAttribute("Name"), "row"))
                                   .findFirst().orElse(null);
                        if (firstRow != null) {
                            firstRow.click();
                            matches = element.findElements(By.xpath(LOCATOR_NEW_ROW));
                        }
                        if (CollectionUtils.isEmpty(matches)) {
                            throw new IllegalArgumentException(
                                msgPrefix + "Unable to retrieve any row; no 'Add Row' found");
                        }
                    }
                } else {
                    // old table/datagrid acts weird some times... we need to click on it, it seems
                    // element.click();

                    // get the 'Add Row' element for new row and then get all the child elements with '*[@Name!='Column Headers']'
                    matches = element.findElements(By.xpath(LOCATOR_NEW_ROW));
                    if (CollectionUtils.isEmpty(matches)) {
                        try { Thread.sleep(5000);} catch (InterruptedException e) {}
                        matches = element.findElements(By.xpath(LOCATOR_NEW_ROW));
                    }

                    // in case the 'Add Row' not available, fetch the elements with previous row (row - 1)
                    if (CollectionUtils.isEmpty(matches)) {
                        String cellsXpath = StringUtils.replace(LOCATOR_CELLS, "{row}", (row - 1) + "");
                        ConsoleUtils.log("fetching columns for previous row" + msgPrefix + "via " + cellsXpath);
                        columns = element.findElements(By.xpath(cellsXpath));
                        for (WebElement column : columns) {
                            String editableColumnName = findAndSetEditableColumnName(column);
                            if (editableColumnName != null) {
                                column.click();
                                matches = element.findElements(By.xpath(LOCATOR_NEW_ROW));
                                if (CollectionUtils.isNotEmpty(matches)) { break; }
                            }
                        }
                    }
                }

                WebElement addRow = matches.get(0);
                // addRow.click();

                String cellsXpath = "*[@Name!='Column Headers']";
                ConsoleUtils.log("fetching columns for " + msgPrefix + "via " + cellsXpath);
                columns = addRow.findElements(By.xpath(cellsXpath));
            } else {

                String xpathRow = StringUtils.replace(LOCATOR_HIER_FIRST_CELL, "{row}", (row + 1) + "");
                matches = element.findElements(By.xpath(xpathRow));
                if (CollectionUtils.isNotEmpty(matches)) {
                    try {
                        matches.get(0).click();
                    } catch (WebDriverException e) {
                        // no biggie here
                        ConsoleUtils.log("Unable to click on Row " + row + "; this command might not work...");
                    }
                }

                String cellsXpath = StringUtils.replace(LOCATOR_HIER_CELLS, "{row}", (row + 1) + "");
                ConsoleUtils.log("fetching columns for " + msgPrefix + "via " + cellsXpath);
                columns = element.findElements(By.xpath(cellsXpath));
            }
        } else {
            String cellsXpath;
            if (isTreeView) {
                cellsXpath = StringUtils.replace(LOCATOR_HIER_CELLS, "{row}", (row + 1) + "");
            } else {
                String rowName = getMatchingRowName("row " + (row + 1));
                cellsXpath = "*[@Name='" + rowName + "']/*[@Name!='Column Headers']";
            }

            ConsoleUtils.log("fetching columns for " + msgPrefix + "via " + cellsXpath);
            columns = element.findElements(By.xpath(cellsXpath));
        }

        if (CollectionUtils.isEmpty(columns)) { throw new IllegalArgumentException(msgPrefix + "No columns found"); }

        // collect names so we can reuse them
        boolean editableColumnFound = context != null &&
                                      context.getBooleanData(CURRENT_DESKTOP_TABLE_EDITABLE_COLUMN_FOUND);

        Map<String, WebElement> columnMapping = new ListOrderedMap<>();
        DesktopTableRow tableRow = new DesktopTableRow();
        for (WebElement column : columns) {
            String columnName = treatColumnHeader(column.getAttribute("Name"));
            if (!editableColumnFound) { findAndSetEditableColumnName(column); }
            columnMapping.put(columnName, column);
        }

        tableRow.setRow(row);
        tableRow.setNewRow(newRow);
        tableRow.setColumns(columnMapping);
        tableRow.setTable(this);
        return tableRow;
    }

    private boolean clickBeforeEdit() {
        ExecutionContext context = ExecutionThread.get();
        return context.getBooleanData(CURRENT_DESKTOP_TABLE_CLICK_BEFORE_EDIT, DEF_DESKTOP_TABLE_CLICK_BEFORE_EDIT);
    }

    public StepResult editCells(Integer row, Map<String, String> nameValues) {
        if (MapUtils.isEmpty(nameValues)) { throw new IllegalArgumentException("No name-value pairs provided"); }

        // check nameValues to make sure all referenced columns are valid
        nameValues.keySet().forEach(column -> {
            column = treatColumnHeader(column);
            if (StringUtils.isBlank(column)) { throw new IllegalArgumentException("Blank/empty column found"); }
            if (!headers.contains(column)) {
                throw new IllegalArgumentException("column '" + column + "' not found in Table object");
            }
        });

        DesktopTableRow tableRow = fetchOrCreateRow(row);
        if (tableRow == null || MapUtils.isEmpty(tableRow.getColumns())) {
            return StepResult.fail("Unable to fetch or create new '" + row + "'");
        }

        return editCells(tableRow, nameValues);
    }

    public StepResult editCells(DesktopTableRow tableRow, Map<String, String> nameValues) {

        if (tableRow == null || MapUtils.isEmpty(tableRow.getColumns())) {
            return StepResult.fail("Unable to edit cells since table row is not valid");
        }

        Map<String, WebElement> columnMapping = tableRow.getColumns();
        StringBuilder messageBuffer = new StringBuilder();

        ExecutionContext context = ExecutionThread.get();

        // loop through each nameValues pairs
        WebElement cellElement;

        boolean focused = true;
        boolean setFocusOut = false;
        for (Map.Entry<String, String> nameValue : nameValues.entrySet()) {

            //todo: support function keys along with [CLICK]? and [CHECK]
            if (context.getBooleanData(DESKTOP_DIALOG_LOOKUP, DEF_DESKTOP_DIALOG_LOOKUP)) {
                // fail if any modal dialog present
                WebElement modal = DesktopCommand.getModalDialog(getCurrentSession().getApp().getElement());
                if (modal != null) {
                    return StepResult.fail("Table cannot be edited when the dialogue box '%s' is present",
                                           modal.getAttribute("Name"));
                }
            }

            String column = treatColumnHeader(nameValue.getKey());
            String value = nameValue.getValue();

            String msgPrefix2 = "Table '" + label + "' Row '" + tableRow.getRow() + "' Column '" + column + "' ";

            cellElement = columnMapping.get(column);
            if (cellElement == null) { return StepResult.fail("Unable to edit " + msgPrefix2 + ": NOT FOUND"); }

            if (StringUtils.equals(value, TABLE_CELL_CLICK)) {
                ConsoleUtils.log("clicked on " + msgPrefix2);
                focused = false;
                context.setData(CURRENT_DESKTOP_TABLE_ROW, tableRow);

                actionClick(cellElement);
                continue;
            }

            ConsoleUtils.log("editing " + msgPrefix2);
            if (isInvokePatternAvailable(cellElement)) { cellElement.click(); }
            ConsoleUtils.log("clicked at (0,0) on " + msgPrefix2);

            if (StringUtils.equals(value, TABLE_CELL_CHECK) || StringUtils.equals(value, TABLE_CELL_UNCHECK)) {

                WebElement checkboxElement;
                boolean isChecked = false;

                // sometimes the checkbox hides under another element by the same name (WEIRD .NET CRAP)
                String columnXpath = resolveColumnXpath(column);
                List<WebElement> matches = cellElement.findElements(By.xpath(columnXpath));
                if (CollectionUtils.isNotEmpty(matches)) {
                    ConsoleUtils.log(msgPrefix2 + " found surrogate (inner) element");
                    checkboxElement = matches.get(0);
                    isChecked = BooleanUtils.toBoolean(StringUtils.trim(checkboxElement.getText()));
                    ConsoleUtils.log("checkbox element is currently " + (isChecked ? "CHECKED" : "UNCHECKED"));
                } else {
                    checkboxElement = findCellCheckboxElement(cellElement);
                    if (checkboxElement != null) { isChecked = checkboxElement.isSelected(); }
                }

                if (checkboxElement == null) {
                    messageBuffer.append(msgPrefix2).append("contains NO checkbox or radio element" + NL);
                    return StepResult.fail(messageBuffer.toString());
                }

                if (!checkboxElement.isEnabled()) {
                    messageBuffer.append(msgPrefix2).append("is NOT ENABLED" + NL);
                    return StepResult.fail(messageBuffer.toString());
                }

                if (isChecked) {
                    if (StringUtils.equals(value, TABLE_CELL_UNCHECK)) {
                        ConsoleUtils.log("unchecking it...");

                        driver.executeScript(toShortcuts("SPACE", "TAB"), checkboxElement);

                        if (context.getBooleanData(CURRENT_DESKTOP_TABLE_EDITABLE_COLUMN_FOUND) &&
                            context.getStringData(CURRENT_DESKTOP_TABLE_EDITABLE_COLUMN_NAME).contentEquals(column)) {
                            setFocusOut = true;
                        }

                        messageBuffer.append(msgPrefix2).append("unchecked").append(NL);
                    }
                } else {
                    if (StringUtils.equals(value, TABLE_CELL_CHECK)) {
                        ConsoleUtils.log("checking it...");

                        driver.executeScript(toShortcuts("SPACE", "TAB"), checkboxElement);

                        if (context.getBooleanData(CURRENT_DESKTOP_TABLE_EDITABLE_COLUMN_FOUND) &&
                            context.getStringData(CURRENT_DESKTOP_TABLE_EDITABLE_COLUMN_NAME).contentEquals(column)) {
                            setFocusOut = true;
                        }

                        messageBuffer.append(msgPrefix2).append("checked").append(NL);
                    }
                }
            } else if (StringUtils.isEmpty(value) || StringUtils.equals(value, TABLE_CELL_CLEAR)) {
                clearCellContent(cellElement);
                messageBuffer.append(msgPrefix2).append("content cleared").append(NL);
            } else {
                // focused would be false if shortcut key is used
                focused = typeCellContent(tableRow, cellElement, column, value);
                messageBuffer.append(msgPrefix2).append("entered text '").append(value).append("'").append(NL);
            }
        }

        if (focused) {
            // todo: need to handle first column = null problem
            if (setFocusOut) {
                looseCurrentFocus();
            } else if (context.getBooleanData(CURRENT_DESKTOP_TABLE_EDITABLE_COLUMN_FOUND)) {
                WebElement editColumn = tableRow.getColumns()
                                                .get(context.getStringData(CURRENT_DESKTOP_TABLE_EDITABLE_COLUMN_NAME));
                // using click action on editable column helps the driver find child elements
                try { editColumn.click(); } catch (Exception e) { }
            }
        }

        return StepResult.success(StringUtils.trim(messageBuffer.toString()));
    }

    public boolean clickFirstMatchedRow(Map<String, String> nameValues) {
        if (MapUtils.isEmpty(nameValues)) { return false; }

        // check names
        StringBuilder xpathMatchColumn = new StringBuilder();
        for (String name : nameValues.keySet()) {
            if (!headers.contains(name)) {
                throw new IllegalArgumentException("Column '" + name + "' is not valid for this Table");
            }

            xpathMatchColumn.append("./*[")
                            .append(resolveColumnXpathCondition(name))
                            .append(" and @Value='").append(nameValues.get(name))
                            .append("'] and ");
        }

        String xpath = "*[" + StringUtils.removeEnd(xpathMatchColumn.toString(), " and ") + "]";
        ConsoleUtils.log("finding/clicking on first matched row via " + xpath);
        List<WebElement> elements = element.findElements(By.xpath(xpath));
        if (CollectionUtils.isEmpty(elements)) { return false; }

        WebElement row = elements.get(0);
        row.click();
        driver.executeScript(SCRIPT_PREFIX_SHORTCUT + "<[ESC]><[CTRL-SPACE]>", row);
        return true;
    }

    // todo: handle the duplicate code
    protected static DesktopSession getCurrentSession() {
        ExecutionContext context = ExecutionThread.get();
        if (!context.hasData(CURRENT_DESKTOP_SESSION)) { return null; }

        Object sessionObj = context.getObjectData(CURRENT_DESKTOP_SESSION);
        if (!(sessionObj instanceof DesktopSession)) {
            context.removeData(CURRENT_DESKTOP_SESSION);
            return null;
        }

        DesktopSession session = (DesktopSession) sessionObj;
        if (session.getApp() == null) {
            ConsoleUtils.error("desktop session found without 'app'; return null!");
            return null;
        }

        if (StringUtils.isBlank(session.getAppId())) {
            ConsoleUtils.error("desktop session found without 'App ID'; return null!");
            return null;
        }

        return session;
    }

    protected void actionClick(WebElement element) { new Actions(getDriver()).moveToElement(element).click().perform();}

    protected String findAndSetEditableColumnName(WebElement column) {
        int tableXoffset = 0;
        BoundingRectangle tableBoundary = BoundingRectangle.newInstance(element);
        if (tableBoundary != null) { tableXoffset = tableBoundary.getX(); }

        BoundingRectangle columnBoundary = BoundingRectangle.newInstance(column);
        int columnXoffset = 0;
        int columnWidth = 0;
        if (columnBoundary != null) {
            columnXoffset = columnBoundary.getX();
            columnWidth = columnBoundary.getWidth();
        }

        if (clickOffsetX > (columnXoffset - tableXoffset) &&
            clickOffsetX < (columnXoffset - tableXoffset + columnWidth)) {
            String editColumnName = treatColumnHeader(column.getAttribute("Name"));
            ExecutionContext context = ExecutionThread.get();
            if (context != null) {
                context.setData(CURRENT_DESKTOP_TABLE_EDITABLE_COLUMN_FOUND, true);
                context.setData(CURRENT_DESKTOP_TABLE_EDITABLE_COLUMN_NAME, editColumnName);
            }
            return editColumnName;
        }

        return null;
    }

    protected WebElement findCellCheckboxElement(WebElement cellElement) {
        // search for the CHECKBOX child element
        String xpath = "*[@ControlType='" + CHECK_BOX + "']";

        // 1. could be under cell (esp. if the target cell is a TEXTBOX)
        List<WebElement> matches = cellElement.findElements(By.xpath(xpath));
        if (!CollectionUtils.isEmpty(matches)) {
            ConsoleUtils.log("Found CHECKBOX element via CELL");
            return matches.get(0);
        }

        // 2. could be under TABLE (esp. if the target cell is a COMBO)
        matches = element.findElements(By.xpath(xpath));
        if (!CollectionUtils.isEmpty(matches)) {
            ConsoleUtils.log("Found CHECKBOX element via TABLE");
            return matches.get(0);
        }

        return null;
    }

    protected void sanityCheck(int row, String column) {
        sanityCheck();

        if (row < 0) { throw new IllegalArgumentException("row (zero-based) must be 0 or greater"); }
        if (CollectionUtils.isEmpty(headers)) { scanStructure(); }
        if (CollectionUtils.isEmpty(headers)) {
            throw new IllegalStateException("Unable to resolve metadata for Table '" + getLabel() + "'");
        }
        if (StringUtils.isNotEmpty(column) && !headers.contains(treatColumnHeader(column))) {
            throw new IllegalArgumentException("column '" + column + "' not found in Table object");
        }
    }

    protected void clickOffset(WebElement element, int x, int y) {
        ConsoleUtils.log("clicking on table via offset (" + x + "," + y + ")");
        new Actions(getDriver()).moveToElement(element, x, y).click().perform();
    }

    protected boolean clickElement(String locator) {
        try {
            WebElement cell = element.findElement(By.xpath(locator));
            if (cell == null) { return false; }
            actionClick(cell);
            return true;
        } catch (NoSuchElementException e) {
            ConsoleUtils.error("No cell retrieved via " + locator + " table '" + getLabel() + "'");
            return false;
        }
    }

    protected void sanityCheck() {
        String errorPrefix = "Failed to scan table: ";
        if (StringUtils.isBlank(getXpath())) {
            throw new IllegalStateException(errorPrefix + "No corresponding desktop element");
        }
        if (driver == null) { throw new IllegalStateException(errorPrefix + "No driver associated to this instance"); }
        if (element == null) { element = driver.findElement(By.xpath(getXpath())); }
    }

    /** support PolyMatcher, as of v3.7 */
    protected boolean containsAnywhere(List<List<String>> data, List<String> expected) {
        // if there's no data to check, and we aren't checking anything -- then that's a PASS (and weird)
        if (CollectionUtils.isEmpty(expected)) { return CollectionUtils.isEmpty(data); }
        return data.stream().anyMatch(row -> contains(row, expected));
    }

    /** support PolyMatcher, as of v3.7 */
    protected boolean containsColumnData(String column, String... text) {
        if (ArrayUtils.isEmpty(text)) { return true; }

        int pos = resolveColumnIndex(column);
        if (pos == UNDEFINED) { return false; }

        List<String> actual = fetchAll().getRows()
                                        .stream()
                                        .filter(Objects::nonNull)
                                        .map(row -> row.get(pos))
                                        .collect(Collectors.toList());
        return contains(actual, Arrays.asList(text));
    }

    /** support PolyMatcher, as of v3.7 */
    protected boolean containsRowData(int row, String... text) {
        List<String> data = fetch(row, row).getRows().get(0);
        return isValidRow(data, row) && contains(data, Arrays.asList(text));
    }

    /** support PolyMatcher, as of v3.7 */
    protected List<List<String>> findMatchedRows(List<String> criterion) {
        List<List<String>> tableRows = fetchAll().getRows();
        if (tableRows.isEmpty()) { throw new IllegalStateException("Table does not contain any data"); }
        return tableRows.stream().filter(row -> contains(row, criterion)).collect(Collectors.toList());
    }

    protected int resolveColumnIndex(String column) {
        if (StringUtils.isBlank(column)) {
            ConsoleUtils.log("Invalid column name specified: " + column);
            return UNDEFINED;
        }

        if (CollectionUtils.isEmpty(headers)) {
            ConsoleUtils.log("Empty headers or header information not yet collected");
            return UNDEFINED;
        }

        column = treatColumnHeader(column);
        int pos = headers.indexOf(column);
        if (pos < 0) {
            ConsoleUtils.log("Invalid column name specified: " + column);
            return UNDEFINED;
        }

        return pos;
    }

    protected boolean isValidRow(List<String> data, int row) {
        String errPrefix = "Invalid row number specified: " + row + ". ";

        if (row < 0) {
            ConsoleUtils.log(errPrefix + "MUST START FROM 0 (0 is FIRST ROW).");
            return false;
        }

        if (CollectionUtils.isEmpty(data)) {
            ConsoleUtils.log(errPrefix + "NO DATA YET COLLECTED FROM CORRESPONDING TABLE");
            return false;
        }

        return true;
    }

    /** support PolyMatcher, as of v3.7 */
    protected boolean contains(List<String> actual, List<String> expected) {
        // the specified row is also empty or contains only empty string, then this is a match
        if (CollectionUtils.isEmpty(expected) || TextUtils.countMatches(expected, "") == expected.size()) {
            return CollectionUtils.isEmpty(actual) || actual.contains("");
        }

        for (String expectedValue : expected) {
            if (StringUtils.isEmpty(expectedValue)) { continue; }

            if (PolyMatcher.isPolyMatcher(expectedValue)) {
                if (!TextUtils.polyMatch(actual, expectedValue)) {
                    ConsoleUtils.log("EXPECTED value '" + expectedValue + "' is NOT poly-matched in " + actual);
                    return false;
                }
            } else if (!actual.contains(expectedValue)) {
                ConsoleUtils.log("EXPECTED value '" + expectedValue + "' is NOT found in " + actual);
                return false;
            }
        }

        return true;
    }

    protected String toString(List<List<String>> data) {
        if (CollectionUtils.isEmpty(data)) { return ""; }
        StringBuilder buffer = new StringBuilder();
        for (List<String> row : data) {
            for (String cell : row) { buffer.append("[").append(cell).append("]"); }
            buffer.append("\n");
        }
        return buffer.toString();
    }

    protected void typeValue(WebElement cellElement, String value, boolean isFormattedText) {
        if (isFormattedText) {
            ConsoleUtils.log("clear content and send keys on formatted textbox '" +
                             treatColumnHeader(cellElement.getAttribute("Name") + "'"));
            setValue(cellElement, "");
            // formatted text box needs special treatment (using both set value and send keys)
            String[] chars = value.split("");
            String value2 = chars[chars.length - 1];
            String value1 = StringUtils.removeEnd(value, value2);
            if (StringUtils.isNotBlank(value1)) { setValue(cellElement, value1); }
            // used actions.sendKeys to support key event driven data cells
            new Actions(getDriver()).moveToElement(cellElement).sendKeys(value2).perform();
        } else {
            ExecutionContext context = ExecutionThread.get();
            // sometimes no-formatted textbox cells required to be cleared
            // might need rework
            if (context.getBooleanData(CLEAR_TABLE_CELL_BEFORE_EDIT, getDefaultBool(CLEAR_TABLE_CELL_BEFORE_EDIT))) {
                setValue(cellElement, "");
            }
            setValue(cellElement, value);
        }
    }

    // todo: not used?
    private List<WebElement> getRowElements() {
        List<WebElement> rows = new ArrayList<>();
        List<WebElement> dataElements = element.findElements(By.xpath(LOCATOR_TABLE_DATA));
        if (CollectionUtils.isNotEmpty(dataElements)) {
            dataElements.forEach(elem -> { if (elem != null) { rows.add(elem); } });
        }
        return rows;
    }

    private void looseCurrentFocus() {
        DesktopSession session = getCurrentSession();
        requiresNotNull(session, "No active desktop session found");

        IgExplorerBar explorerBar = session.findThirdPartyComponent(IgExplorerBar.class);
        if (explorerBar != null) {
            // todo: need to configure the element to click on per application
            explorerBar.clickFirstSectionHeader();
        } else {
            ConsoleUtils.log("no explorer bar component specified/loaded; try pressing escape key...");
            new Actions(driver).sendKeys(ESCAPE);
        }
    }

    // not used?
    private List<WebElement> rescanNullColumns(List<WebElement> columns, String cellsXpath) {
        List<WebElement> cols = new ArrayList<>();
        columns.forEach(column -> {
            if (column.getAttribute("Name") == null) {
                WebElement col = element.findElement(By.xpath(cellsXpath));
                if (col.getAttribute("Name") != null) {
                    cols.add(col);
                }
            }
        });

        return cols;
    }

    private void clearCellContent(WebElement cellElement) { driver.executeScript(SCRIPT_SET_VALUE, cellElement, ""); }

    private int collectRowCountByXpath() {
        // collect row count using xpath
        List<WebElement> dataElements = element.findElements(By.xpath(LOCATOR_TABLE_DATA));
        if (CollectionUtils.isEmpty(dataElements)) {
            ConsoleUtils.log("No rows found for data grid " + element);
        }
        return CollectionUtils.size(dataElements);
    }

    private String treatColumnHeader(String header) {
        return normalizeSpace ? TextUtils.xpathNormalize(header) : header;
    }

    private String resolveColumnXpath(String column) { return "*[" + resolveColumnXpathCondition(column) + "]"; }

    private String resolveColumnXpathCondition(String column) {
        return (normalizeSpace ? "normalize-space(@Name)='" : "@Name='") + column + "'";
    }

    private boolean typeCellContent(DesktopTableRow tableRow, WebElement cellElement, String column, String value) {
        // special treatment for shortcut
        // special treatment for formatted textbox
        if (MapUtils.isNotEmpty(cellTypes) && cellTypes.containsKey(column)) {
            String cellType = cellTypes.get(column);
            try {
                ElementType elementTypeHint = ElementType.valueOf(cellType);
                if (elementTypeHint == FormattedTextbox) {
                    return typeValueWithFunctionKey(tableRow, cellElement, value);
                }
            } catch (IllegalArgumentException e) {
                // never mind...
                ConsoleUtils.error("Unknown overridden cell type: " + cellType + e.getMessage());
                return true;
            }
        }

        String controlType = cellElement.getAttribute("ControlType");
        if (StringUtils.equals(controlType, COMBO)) {
            // while combo usually contains edit element from which we can type in value, seeking for such element might
            // dramatically increase execution time esp. when the combo in question contains many selection items.
            // therefore for combo, we would directly enter text (and trust that) and then evaluate the combo's value
            // afterwards
            return typeValueWithFunctionKey(tableRow, cellElement, value);
        }

        String currentValue = getCellValue(cellElement);
        if (StringUtils.equals(currentValue, value)) {
            ConsoleUtils.log("Text '" + value + "' already entered into cell '" + column + "'");
            return true;
        }

        if (isValuePatternAvailable(cellElement)) {
            // the code below is not stable for combo.. since some combo do not receive 'text changed' event until focus is lost.
            // ConsoleUtils.log("using setValue on '" + column + "' on '" + cellElement.getAttribute("ControlType") + "'");
            // driver.executeScript("automation: ValuePattern.SetValue", cellElement, value);

            // do I have Edit component under Table?
            /*
            List<WebElement> matches = cellElement.findElements(By.xpath("*[@ControlType='" + EDIT + "']"));
            if (CollectionUtils.isEmpty(matches) && isInvokePatternAvailable(cellElement)) {
                // the code below is alternative for combo which do not have 'Edit' component under Table even if it is editable.
                // This is the case when sometime we can't type text in combo box of table row, need to select from dropdowns
                ConsoleUtils.log("using shortcut on '" + column + "'");
                driver.executeScript(SCRIPT_PREFIX_SHORTCUT +
                                     (StringUtils.isNotEmpty(currentValue) ? "<[HOME]><[SHIFT-END]><[DEL]>" : "") +
                                     forceShortcutSyntax(value), cellElement);
                return true;
            }
            */

            ConsoleUtils.log("type '" + value + "' on '" + column + "'");
            return typeValueWithFunctionKey(tableRow, cellElement, value);
        } else if (isTextPatternAvailable(cellElement)) {
            ConsoleUtils.log("using shortcut on '" + column + "'");
            driver.executeScript(SCRIPT_PREFIX_SHORTCUT + forceShortcutSyntax(value), cellElement);
            return true;
        } else {
            ConsoleUtils.log("using sendKeys on '" + column + "'");
            return typeValueWithFunctionKey(tableRow, cellElement, value);
        }
    }

    private boolean typeValueWithFunctionKey(DesktopTableRow tableRow, WebElement cellElement, String value) {
        ExecutionContext context = ExecutionThread.get();
        if (TextUtils.isBetween(value, "[", "]")) {
            ConsoleUtils.log("enter shortcut keys " + value + " on '" + label + "'");
            context.setData(CURRENT_DESKTOP_TABLE_ROW, tableRow);
            driver.executeScript(toShortcuts(StringUtils.substringBetween(value, "[", "]")), cellElement);
            return false;
        }

        String currentValue = getCellValue(cellElement);
        String shortcutPrefix = SCRIPT_PREFIX_SHORTCUT +
                                (StringUtils.isNotEmpty(currentValue) ? "<[HOME]><[SHIFT-END]><[DEL]>" : "");

        Pattern p = Pattern.compile("^(.+)\\[(.+)]$");
        Matcher m = p.matcher(value);
        if (m.find()) {
            // extract combo selection
            value = m.group(1);
            // extract what's between [...]
            String postShortcut = m.group(2);

            if (context != null) { context.setData(CURRENT_DESKTOP_TABLE_ROW, tableRow); }

            driver.executeScript(shortcutPrefix + forceShortcutSyntax(value) +
                                 (StringUtils.isNotBlank(postShortcut) ? toShortcuts(postShortcut) : ""),
                                 cellElement);
            return false;
        } else {
            driver.executeScript(shortcutPrefix + forceShortcutSyntax(value), cellElement);
            return true;
        }

    }

    @Nullable
    private String getCellValue(WebElement cellElement) {
        try { return cellElement.getText(); } catch (WebDriverException e) { /* ignore... */ }
        return null;
    }
}


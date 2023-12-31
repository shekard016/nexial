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

package org.nexial.core.plugins.base;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.nexial.core.utils.ConsoleUtils;
import org.openqa.selenium.Point;

import java.awt.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

import static java.awt.event.KeyEvent.*;
import static org.nexial.core.NexialConst.TARGET_DISPLAY;
import static org.nexial.core.NexialConst.TARGET_DISPLAY_CURRENT;

public final class AwtUtils {
    private static final Map<Character, Integer> SHIFT_NEEDED = initShiftNeededMapping();
    private static final Map<Character, Integer> TRANSLATION_NEEDED = initTranslationNeededMapping();
    private static final String FN_SHIFT = "Shift";
    private static final String FN_CONTROL = "Ctrl";
    private static final String FN_ALT = "Alt";
    private static final String PREFIX = "[DISPLAY] ";

    private static final ThreadLocal<Integer> SEMAPHORE = initSemaphore();

    private static Window window;
    private static GraphicsDevice graphicDevice;
    private static Robot robot;
    private static GraphicsConfiguration graphicsConfig;

    private AwtUtils() { }

    public static GraphicsDevice getGraphicsDevice() {
        ensureReady();
        return graphicDevice;
    }

    public static GraphicsConfiguration getGraphicsConfiguration() {
        ensureReady();
        return graphicsConfig;
    }

    public static Robot getRobotInstance() {
        ensureReady();
        return robot;
    }

    @Nullable
    public static Dimension getScreenDimension(int monitorIndex) {
        GraphicsDevice[] screens = getAvailableScreens();
        if (screens == null) { return null; }
        if (ArrayUtils.isEmpty(screens)) { return null; }
        if (monitorIndex < 0 || screens.length <= monitorIndex) { monitorIndex = 0; }

        GraphicsConfiguration[] screenConfig = screens[monitorIndex].getConfigurations();

        Dimension allBounds = new Dimension();
        allBounds.width = screenConfig[0].getBounds().width;
        allBounds.height = screenConfig[0].getBounds().height;
        return allBounds;
    }

    @Nullable
    public static GraphicsDevice[] getAvailableScreens() {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            return ge == null ? null : ge.getScreenDevices();
        } catch (Throwable e) {
            ConsoleUtils.error("Error when determining available screen(s): " + e.getMessage());
            return null;
        }
    }

    public static boolean isValidScreen(int target) {
        if (target < 0) { return false; }

        GraphicsDevice[] screens = AwtUtils.getAvailableScreens();
        return screens != null && ArrayUtils.isNotEmpty(screens) && screens.length > target;
    }

    public static void typeKey(String text) {
        if (StringUtils.isEmpty(text)) { throw new IllegalArgumentException("text is blank/null"); }

        Robot robot = getRobotInstance();

        text = StringUtils.replace(text, "[\\n]", "\n");
        text = StringUtils.replace(text, "[\\t]", "\t");

        char[] chars = text.toCharArray();
        for (char ch : chars) {

            Integer keyCode = SHIFT_NEEDED.get(ch);
            if (keyCode != null) {
                robot.keyPress(VK_SHIFT);
                robot.keyPress(keyCode);
                robot.keyRelease(keyCode);
                robot.keyRelease(VK_SHIFT);
                continue;
            }

            keyCode = TRANSLATION_NEEDED.get(ch);
            if (keyCode != null) {
                robot.keyPress(keyCode);
                robot.keyRelease(keyCode);
                continue;
            }

            boolean isUpper = ch >= 'A' && ch <= 'Z';
            if (isUpper) { robot.keyPress(VK_SHIFT); }
            robot.keyPress(Character.toUpperCase(ch));
            robot.keyRelease(Character.toUpperCase(ch));
            if (isUpper) { robot.keyRelease(VK_SHIFT); }

        }
    }

    public static void typeShortcut(String functionKeys, String key) {
        if (StringUtils.isBlank(functionKeys)) { throw new IllegalArgumentException("functionKeys is blank/null"); }
        if (StringUtils.isEmpty(key)) { throw new IllegalArgumentException("key is blank/null"); }
        if (StringUtils.length(key) != 1) { throw new IllegalArgumentException("key must be exactly 1 character"); }

        String[] fnKeys = StringUtils.split(functionKeys, "-");
        if (ArrayUtils.isEmpty(fnKeys)) { fnKeys = new String[]{functionKeys}; }

        Robot robot = getRobotInstance();

        for (String fnKey : fnKeys) {
            if (StringUtils.equals(fnKey, FN_SHIFT)) {
                robot.keyPress(VK_SHIFT);
            } else if (StringUtils.equals(fnKey, FN_CONTROL)) {
                robot.keyPress(VK_CONTROL);
            } else if (StringUtils.equals(fnKey, FN_ALT)) {
                robot.keyPress(VK_ALT);
            } else {
                ConsoleUtils.error("Unknown function key: " + fnKey);
            }
        }

        robot.keyPress(Character.toUpperCase(key.charAt(0)));
        robot.keyRelease(Character.toUpperCase(key.charAt(0)));

        ArrayUtils.reverse(fnKeys);
        for (String fnKey : fnKeys) {
            if (StringUtils.equals(fnKey, FN_SHIFT)) {
                robot.keyRelease(VK_SHIFT);
            } else if (StringUtils.equals(fnKey, FN_CONTROL)) {
                robot.keyRelease(VK_CONTROL);
            } else if (StringUtils.equals(fnKey, FN_ALT)) {
                robot.keyRelease(VK_ALT);
            } else {
                ConsoleUtils.error("Unknown function key: " + fnKey);
            }
        }
    }

    public static void mouseMove(int x, int y) {
        Robot robot = getRobotInstance();
        robot.mouseMove(x, y);
    }

    public static Point relativeToTargetDisplay(Point position) {
        String targetDisplayConf = System.getProperty(TARGET_DISPLAY);
        GraphicsDevice screen = null;
        if (StringUtils.equals(targetDisplayConf, TARGET_DISPLAY_CURRENT)) {
            screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            ConsoleUtils.log(PREFIX + "adjust position to CURRENT display " + screen.getDisplayMode());
        } else {
            int targetDisplay = Math.max(NumberUtils.toInt(targetDisplayConf, 0), 0);
            GraphicsDevice[] screens = getAvailableScreens();
            if (screens == null) {
                ConsoleUtils.error(PREFIX + "Unable to obtain current display configuration; position remains AS IS");
            } else {
                ConsoleUtils.log(PREFIX + "Currently discovered display(s):");
                Arrays.stream(screens).forEach(s -> ConsoleUtils.log("\t" + s.getDisplayMode()));
                if (screens.length <= targetDisplay) {
                    ConsoleUtils.error(PREFIX + "No DISPLAY " + targetDisplay + " found; use current screen instead");
                    screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
                } else {
                    screen = screens[targetDisplay];
                }
                ConsoleUtils.log(PREFIX + "adjust position to display " + screen.getDisplayMode());
            }
        }

        if (screen != null) {
            GraphicsConfiguration screenConfig = screen.getDefaultConfiguration();
            if (screenConfig == null || screenConfig.getBounds() == null) {
                ConsoleUtils.error(PREFIX + "Unable to obtain screen boundary; position remains AS IS");
            } else {
                Rectangle bounds = screenConfig.getBounds();
                ConsoleUtils.log(PREFIX + "adjusting position within the bounds of target display: " +
                                 StringUtils.substringBetween(Objects.toString(bounds, "[UNKNOWN]"), "[", "]"));
                position = new Point((int) bounds.getX() + position.getX(),
                                     (int) bounds.getY() + position.getY());
                ConsoleUtils.log(PREFIX + "position adjusted to " + position);
            }
        }

        return position;
    }

    /**
     * helper method to forcefully terminate a Nexial runtime in case gui/awt is used and AWTEvent/listener
     * is still running.
     */
    static boolean mustTerminateForcefully() {
        if (window == null) { return false; }

        Graphics graphics = window.getGraphics();
        if (graphics != null) { graphics.dispose(); }

        Window owner = window.getOwner();
        if (owner != null) { owner.dispose(); }

        window.dispose();
        return true;
    }

    private static Map<Character, Integer> initShiftNeededMapping() {
        HashMap<Character, Integer> map = new HashMap<>();
        map.put('~', VK_BACK_QUOTE);
        map.put('!', VK_1);
        map.put('@', VK_2);
        map.put('#', VK_3);
        map.put('$', VK_4);
        map.put('%', VK_5);
        map.put('^', VK_6);
        map.put('&', VK_7);
        map.put('*', VK_8);
        map.put('(', VK_9);
        map.put(')', VK_0);
        map.put('_', VK_MINUS);
        map.put('+', VK_EQUALS);
        map.put('{', VK_OPEN_BRACKET);
        map.put('}', VK_CLOSE_BRACKET);
        map.put('|', VK_BACK_SLASH);
        map.put(':', VK_SEMICOLON);
        map.put('"', VK_QUOTE);
        map.put('<', VK_COMMA);
        map.put('>', VK_PERIOD);
        map.put('?', VK_SLASH);
        return map;
    }

    private static Map<Character, Integer> initTranslationNeededMapping() {
        HashMap<Character, Integer> map = new HashMap<>();
        map.put('\\', VK_BACK_SLASH);
        map.put('/', VK_SLASH);
        map.put('.', VK_PERIOD);
        map.put('-', VK_MINUS);
        map.put('`', VK_BACK_QUOTE);
        map.put('=', VK_EQUALS);
        map.put('[', VK_OPEN_BRACKET);
        map.put(']', VK_CLOSE_BRACKET);
        map.put(';', VK_SEMICOLON);
        map.put('\'', VK_QUOTE);
        map.put(',', VK_COMMA);
        map.put(' ', VK_SPACE);
        map.put('\n', VK_ENTER);
        map.put('\t', VK_TAB);
        return map;
    }

    private static ThreadLocal<Integer> initSemaphore() {
        ThreadLocal<Integer> semaphore = new ThreadLocal<>();
        semaphore.set(new AtomicInteger(RandomUtils.nextInt(0, Integer.MAX_VALUE)).getAndDecrement());
        return semaphore;
    }

    private static void ensureReady() {
        synchronized (SEMAPHORE) {
            if (graphicDevice == null) {
                GraphicsEnvironment graphicsEnv = GraphicsEnvironment.getLocalGraphicsEnvironment();
                if (graphicsEnv.isHeadlessInstance()) {
                    throw new RuntimeException("Invalid environment - headless environment found.");
                }

                graphicDevice = graphicsEnv.getDefaultScreenDevice();
                window = graphicDevice.getFullScreenWindow();
                graphicsConfig = graphicDevice.getDefaultConfiguration();
                if (window == null) { window = new Window(new Frame(graphicsConfig), graphicsConfig); }
                try {
                    robot = new Robot(graphicDevice);
                    // robot.setAutoDelay(20);
                } catch (AWTException e) {
                    throw new RuntimeException("Invalid (and possibly headless) environment found: " + e.getMessage());
                }
            }
        }
    }
}

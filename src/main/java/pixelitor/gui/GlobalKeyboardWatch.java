/*
 * Copyright 2018 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 *
 * Pixelitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor.gui;

import pixelitor.menus.view.ShowHideAllAction;
import pixelitor.tools.Tools;
import pixelitor.tools.util.ArrowKey;
import pixelitor.tools.util.KeyListener;
import pixelitor.utils.VisibleForTesting;

import javax.swing.*;
import java.awt.AWTEvent;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * A global listener for keyboard events
 */
public class GlobalKeyboardWatch {
    private static boolean spaceDown = false;
    private static boolean dialogActive = false;
    private static JComponent alwaysVisibleComponent;
    private static KeyListener keyListener;

    private static final List<MappedKey> mappedKeys = new ArrayList<>();

    private static final Action INCREASE_ACTIVE_BRUSH_SIZE_ACTION = new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
            Tools.increaseActiveBrushSize();
        }
    };

    private static final Action DECREASE_ACTIVE_BRUSH_SIZE_ACTION = new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
            Tools.decreaseActiveBrushSize();
        }
    };

    private GlobalKeyboardWatch() {
        // do not instantiate: only static utility methods
    }

    public static void initTab() {
        // we want to use the tab key as "hide all", but
        // tab is the focus traversal key, it must be handled before it gets consumed
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            int id = e.getID();
            if (id == KeyEvent.KEY_PRESSED) {
                keyPressed(e);
            } else if (id == KeyEvent.KEY_RELEASED) {
                keyReleased(e);
            }
            return false;
        });
    }

    private static void keyPressed(KeyEvent e) {
        int keyCode = e.getKeyCode();
        switch (keyCode) {
            case KeyEvent.VK_TAB:
                if (!dialogActive) {
                    ShowHideAllAction.INSTANCE.actionPerformed(null);
                }
                break;
            case KeyEvent.VK_SPACE:
                if (!dialogActive) {
                    keyListener.spacePressed();
                    spaceDown = true;
                    e.consume();
                }

                break;
            case KeyEvent.VK_RIGHT:
            case KeyEvent.VK_KP_RIGHT:
                // checking for VK_KP_RIGHT and other KP keys does not seem to be necessary
                // because at least on windows actually VK_RIGHT is sent by the keypad keys
                // but let's check them in order to be on the safe side
                if (!dialogActive && keyListener.arrowKeyPressed(new ArrowKey.RIGHT(e.isShiftDown()))) {
                    e.consume();
                }
                break;
            case KeyEvent.VK_LEFT:
            case KeyEvent.VK_KP_LEFT:
                if (!dialogActive && keyListener.arrowKeyPressed(new ArrowKey.LEFT(e.isShiftDown()))) {
                    e.consume();
                }
                break;
            case KeyEvent.VK_UP:
            case KeyEvent.VK_KP_UP:
                if (!dialogActive && keyListener.arrowKeyPressed(new ArrowKey.UP(e.isShiftDown()))) {
                    e.consume();
                }
                break;
            case KeyEvent.VK_DOWN:
            case KeyEvent.VK_KP_DOWN:
                if (!dialogActive && keyListener.arrowKeyPressed(new ArrowKey.DOWN(e.isShiftDown()))) {
                    e.consume();
                }
                break;
            case KeyEvent.VK_ESCAPE:
                if (!dialogActive) {
                    keyListener.escPressed();
                }
                break;
            case KeyEvent.VK_ALT:
                if (!dialogActive) {
                    keyListener.altPressed();
                }
                break;
            case KeyEvent.VK_SHIFT:
                if (!dialogActive) {
                    keyListener.shiftPressed();
                }
                break;
        }
    }

    private static void keyReleased(KeyEvent e) {
        int keyCode = e.getKeyCode();
        switch (keyCode) {
            case KeyEvent.VK_SPACE:
                keyListener.spaceReleased();
                spaceDown = false;
                break;
            case KeyEvent.VK_ALT:
                if (!dialogActive) {
                    keyListener.altReleased();
                }
                break;
            case KeyEvent.VK_SHIFT:
                if (!dialogActive) {
                    keyListener.shiftReleased();
                }
                break;
        }
    }

    public static boolean isSpaceDown() {
        return spaceDown;
    }

    @VisibleForTesting
    public static void setSpaceDown(boolean spaceDown) {
        GlobalKeyboardWatch.spaceDown = spaceDown;
    }

    /**
     * The idea is that when we are in a dialog, we want to use the Tab
     * key for navigating the UI, and not for "Hide All"
     */
    public static void setDialogActive(boolean dialogActive) {
        GlobalKeyboardWatch.dialogActive = dialogActive;
    }

    public static void setAlwaysVisibleComponent(JComponent alwaysVisibleComponent) {
        GlobalKeyboardWatch.alwaysVisibleComponent = alwaysVisibleComponent;
    }

    public static void add(MappedKey key) {
        // since the "always visible component" can change, we only
        // store the keys, and re-register them after every change
        mappedKeys.add(key);
    }

    public static void registerKeysOnAlwaysVisibleComponent() {
        InputMap inputMap = alwaysVisibleComponent.getInputMap(
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = alwaysVisibleComponent.getActionMap();

        for (MappedKey key : mappedKeys) {
            key.registerOn(inputMap, actionMap);
        }
    }

    public static void addBrushSizeActions() {
        GlobalKeyboardWatch.add(
                MappedKey.fromChar(']', false,
                        "increment", INCREASE_ACTIVE_BRUSH_SIZE_ACTION));
        GlobalKeyboardWatch.add(
                MappedKey.fromChar('[', false,
                        "decrement", DECREASE_ACTIVE_BRUSH_SIZE_ACTION));
    }

    public static void registerDebugMouseWatching() {
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            MouseEvent m = (MouseEvent) event;
            String compClass = m.getComponent().getClass().getName();
            if (m.getID() == MouseEvent.MOUSE_CLICKED) {
                System.out.println("GlobalKeyboardWatch:MOUSE_CLICKED"
                        + " x = " + m.getX() + ", y = " + m.getY()
                        + ", click count = " + m.getClickCount()
                        + ", comp class = " + compClass);
            } else if (m.getID() == MouseEvent.MOUSE_DRAGGED) {
                System.out.println("GlobalKeyboardWatch:MOUSE_DRAGGED"
                        + " x = " + m.getX() + ", y = " + m.getY()
                        + ", comp class = " + compClass);
            } else if (m.getID() == MouseEvent.MOUSE_PRESSED) {
                System.out.println("GlobalKeyboardWatch:MOUSE_PRESSED"
                        + " x = " + m.getX() + ", y = " + m.getY()
                        + ", comp class = " + compClass);
            } else if (m.getID() == MouseEvent.MOUSE_RELEASED) {
                System.out.println("GlobalKeyboardWatch:MOUSE_RELEASED"
                        + " x = " + m.getX() + ", y = " + m.getY()
                        + ", comp class = " + compClass);
            }
        }, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
    }

    // TODO this kind of global listening might be better
//    public static void registerMouseWheelWatching() {
//        Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
//            @Override
//            public void eventDispatched(AWTEvent e) {
//            }
//        }, AWTEvent.MOUSE_WHEEL_EVENT_MASK);
//    }


    public static void setKeyListener(KeyListener keyListener) {
        GlobalKeyboardWatch.keyListener = keyListener;
    }
}

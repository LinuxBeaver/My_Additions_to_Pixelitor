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

package pixelitor.tools;

import pixelitor.gui.ImageComponent;
import pixelitor.tools.shapestool.ShapesTool;

import java.awt.event.MouseEvent;
import java.util.Random;

/**
 * Tool-related static utility methods
 */
public class Tools {
    private Tools() {
    }

    public static final MoveTool MOVE = new MoveTool();
    public static final CropTool CROP = new CropTool();
    public static final SelectionTool SELECTION = new SelectionTool();
    //    public static final LassoTool LASSO = new LassoTool();
    public static final BrushTool BRUSH = new BrushTool();
    public static final CloneTool CLONE = new CloneTool();

    public static final EraserTool ERASER = new EraserTool();
    public static final SmudgeTool SMUDGE = new SmudgeTool();
    public static final GradientTool GRADIENT = new GradientTool();
    public static final PaintBucketTool PAINT_BUCKET = new PaintBucketTool();
    public static final ColorPickerTool COLOR_PICKER = new ColorPickerTool();
    public static final ShapesTool SHAPES = new ShapesTool();
    public static final HandTool HAND = new HandTool();
    public static final ZoomTool ZOOM = new ZoomTool();

    static Tool currentTool = BRUSH;

    /**
     * All the subclass tools in an array.
     */
    private static final Tool[] allTools =
            {MOVE, CROP, SELECTION, BRUSH, CLONE, ERASER,
                    SMUDGE,
                    GRADIENT, PAINT_BUCKET, COLOR_PICKER, SHAPES, HAND, ZOOM};

    public static void setDefaultTool() {
        changeTo(BRUSH);
        currentTool.getButton().setSelected(true);
    }

    public static void changeTo(Tool newCurrentTool) {
        Tools.currentTool.toolEnded();
        Tools.currentTool = newCurrentTool;
        newCurrentTool.toolStarted();
        ToolSettingsPanelContainer.INSTANCE.showSettingsFor(newCurrentTool);
    }

    public static Tool[] getAll() {
        return allTools;
    }

    public static Tool getCurrent() {
        return currentTool;
    }

    public static boolean isShapesDrawing() {
        if (currentTool != SHAPES) {
            return false;
        }
        return SHAPES.isDrawing();
    }

    public static void increaseActiveBrushSize() {
        if (currentTool instanceof AbstractBrushTool) {
            ((AbstractBrushTool) currentTool).increaseBrushSize();
        }
    }

    public static void decreaseActiveBrushSize() {
        if (currentTool instanceof AbstractBrushTool) {
            ((AbstractBrushTool) currentTool).decreaseBrushSize();
        }
    }

    public static Tool getRandomTool(Random rand) {
        int index = rand.nextInt(allTools.length);
        return allTools[index];
    }

    public static class EventDispatcher {
        private EventDispatcher() {
        }

        public static void mouseClicked(MouseEvent e, ImageComponent ic) {
            currentTool.dispatchMouseClicked(e, ic);
        }

        public static void mousePressed(MouseEvent e, ImageComponent ic) {
            currentTool.dispatchMousePressed(e, ic);
        }

        public static void mouseReleased(MouseEvent e, ImageComponent ic) {
            currentTool.dispatchMouseReleased(e, ic);
        }

        public static void mouseDragged(MouseEvent e, ImageComponent ic) {
            currentTool.dispatchMouseDragged(e, ic);
        }

        public static void mouseMoved(MouseEvent e, ImageComponent ic) {
            currentTool.dispatchMouseMoved(e, ic);
        }
    }
}

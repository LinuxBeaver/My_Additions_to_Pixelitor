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

import pixelitor.io.DropListener;
import pixelitor.utils.Messages;

import javax.swing.*;
import java.awt.Dimension;
import java.awt.dnd.DropTarget;
import java.beans.PropertyVetoException;
import java.util.List;

import static java.awt.Color.GRAY;

/**
 * The desktop area of the app, where the edited images are.
 * Currently the GUI is a JDesktopPane, but a JTabbedPane
 * could be an alternative.
 */
public class Desktop {
    private static final int CASCADE_HORIZONTAL_SHIFT = 15;
    private static final int CASCADE_VERTICAL_SHIFT = 25;

    public static final Desktop INSTANCE = new Desktop();

    private final JDesktopPane desktopPane;

    private Desktop() {
        desktopPane = new JDesktopPane();
        GlobalKeyboardWatch.setAlwaysVisibleComponent(desktopPane);
        GlobalKeyboardWatch.registerBrushSizeActions();
        new DropTarget(desktopPane, new DropListener());

        desktopPane.setBackground(GRAY);
    }

    public JDesktopPane getDesktopPane() {
        return desktopPane;
    }

    public void activateImageFrame(ImageFrame frame) {
        assert frame != null;
        desktopPane.getDesktopManager().activateFrame(frame);
    }

    public void cascadeWindows() {
        List<ImageComponent> icList = ImageComponents.getICList();
        int locX = 0;
        int locY = 0;
        for (ImageComponent ic : icList) {
            ImageFrame frame = ic.getFrame();
            frame.setLocation(locX, locY);
            frame.setToNaturalSize(locX, locY);
            try {
                frame.setIcon(false);
                frame.setMaximum(false);
            } catch (PropertyVetoException e) {
                e.printStackTrace();
            }

            locX += CASCADE_HORIZONTAL_SHIFT;
            locY += CASCADE_VERTICAL_SHIFT;

            // wrap
            int maxWidth = desktopPane.getWidth() - CASCADE_HORIZONTAL_SHIFT;
            int maxHeight = desktopPane.getHeight() - CASCADE_VERTICAL_SHIFT;

            if (locX > maxWidth) {
                locX = 0;
            }
            if (locY > maxHeight) {
                locY = 0;
            }
        }
    }

    public void tileWindows() {
        List<ImageComponent> icList = ImageComponents.getICList();
        int numWindows = icList.size();

        int numRows = (int) Math.sqrt(numWindows);
        int numCols = numWindows / numRows;
        int extra = numWindows % numRows;

        int frameWidth = desktopPane.getWidth() / numCols;
        int frameHeight = desktopPane.getHeight() / numRows;
        int currRow = 0;
        int currCol = 0;

        for (ImageComponent ic : icList) {
            ImageFrame frame = ic.getFrame();
            try {
                frame.setIcon(false);
                frame.setMaximum(false);
            } catch (PropertyVetoException e) {
                e.printStackTrace();
            }
            int frameX = currCol * frameWidth;
            int frameY = currRow * frameHeight;
            frame.reshape(frameX, frameY, frameWidth, frameHeight);
            currRow++;
            if (currRow == numRows) {
                currRow = 0;
                currCol++;
                if (currCol == numCols - extra) {
                    numRows++;
                    frameHeight = desktopPane.getHeight() / numRows;
                }
            }
        }
    }

    public void addNewIC(ImageComponent ic) {
        int numImages = ImageComponents.getNumOpenImages();

        // called deliberately after numImages is set
        ImageComponents.addIC(ic);

        int locX = CASCADE_HORIZONTAL_SHIFT * numImages;
        int locY = CASCADE_VERTICAL_SHIFT * numImages;

        int maxWidth = desktopPane.getWidth() - CASCADE_HORIZONTAL_SHIFT;
        locX %= maxWidth;

        int maxHeight = desktopPane.getHeight() - CASCADE_VERTICAL_SHIFT;
        locY %= maxHeight;

        ImageFrame frame = new ImageFrame(ic, locX, locY);
        ic.setFrame(frame);

        desktopPane.add(frame);
        try {
            frame.setSelected(true);
            desktopPane.getDesktopManager().activateFrame(frame);
            ImageComponents.newImageOpened(ic.getComp());
        } catch (PropertyVetoException e) {
            Messages.showException(e);
        }
    }

    public Dimension getDesktopSize() {
        return desktopPane.getSize();
    }
}

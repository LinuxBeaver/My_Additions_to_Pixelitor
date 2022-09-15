/*
 * Copyright 2022 Laszlo Balazs-Csiki and Contributors
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

package pixelitor.layers;


import pixelitor.Composition;
import pixelitor.CopyType;
import pixelitor.compactions.Flip;
import pixelitor.history.DeleteLayerEdit;
import pixelitor.history.GroupingEdit;
import pixelitor.history.History;
import pixelitor.history.PixelitorEdit;
import pixelitor.utils.ImageUtils;
import pixelitor.utils.QuadrantAngle;
import pixelitor.utils.debug.DebugNode;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static pixelitor.layers.LayerGUILayout.thumbSize;
import static pixelitor.utils.ImageUtils.createThumbnail;

/**
 * A layer group.
 */
public class LayerGroup extends CompositeLayer {
    @Serial
    private static final long serialVersionUID = 1L;

    private List<Layer> layers;

    private transient BufferedImage thumb;

    // used only for isolated images
    private transient BufferedImage cachedImage;

    public LayerGroup(Composition comp, String name) {
        this(comp, name, new ArrayList<>());
    }

    public LayerGroup(Composition comp, String name, List<Layer> layers) {
        super(comp, name);
        setLayers(layers);
        blendingMode = BlendingMode.PASS_THROUGH;
    }

    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();

        cachedImage = null;
        thumb = null;
    }

    @Override
    public void afterDeserialization() {
        recalculateCachedImage();
    }

    @Override
    protected Layer createTypeSpecificCopy(CopyType copyType, Composition newComp) {
        String copyName = copyType.createLayerCopyName(name);
        List<Layer> layersCopy = new ArrayList<>();
        for (Layer layer : layers) {
            layersCopy.add(layer.copy(copyType, true, newComp));
        }
        LayerGroup copy = new LayerGroup(comp, copyName, layersCopy);
        return copy;
    }

    public void setLayers(List<Layer> layers) {
        this.layers = layers;
        for (Layer layer : layers) {
            layer.setHolder(this);
        }
    }

    public boolean isPassThrough() {
        return blendingMode == BlendingMode.PASS_THROUGH;
    }

    @Override
    public boolean isRasterizable() {
        return !isPassThrough();
    }

    @Override
    public boolean canExportImage() {
        return !isPassThrough();
    }

    @Override
    public BufferedImage applyLayer(Graphics2D g, BufferedImage imageSoFar, boolean firstVisibleLayer) {
        if (isPassThrough()) {
            for (Layer layer : layers) {
                if (layer.isVisible()) {
                    BufferedImage result = layer.applyLayer(g, imageSoFar, firstVisibleLayer);
                    if (result != null) { // adjustment layer or watermarking text layer
                        imageSoFar = result;
                        if (g != null) {
                            g.dispose();
                        }
                        g = imageSoFar.createGraphics();
                    }
                    firstVisibleLayer = false;
                }
            }
        } else {
            // TODO apply mask
            g.setComposite(blendingMode.getComposite(getOpacity()));
            g.drawImage(getCachedImage(), 0, 0, null);
        }
        return imageSoFar;
    }

    @Override
    public void update(Composition.UpdateActions actions) {
        recalculateCachedImage();
        holder.update(actions);
    }

    private void recalculateCachedImage() {
        if (isPassThrough()) {
            cachedImage = null;
        } else {
            cachedImage = ImageUtils.calculateCompositeImage(layers, comp.getCanvas());
        }
    }

    @Override
    public void invalidateImageCache() {
        cachedImage = null;
        holder.invalidateImageCache();
    }

    @Override
    public BufferedImage asImage(boolean applyMask, boolean applyOpacity) {
        // TODO this totally ignores the arguments
        //   (it was only implemented to support the
        //   shortcut in Composition.getCompositeImage)
        // Actually this should return non-null only if
        //  canExportImage() returns true, but if this group is the
        // single layer (or the first layer!) then even a pass-trough
        // group behaves like an isolated one
        if (isPassThrough()) {
            return ImageUtils.calculateCompositeImage(layers, comp.getCanvas());
        } else {
            return getCachedImage();
        }
    }

    private BufferedImage getCachedImage() {
        if (cachedImage == null) {
            recalculateCachedImage();
        }
        return cachedImage;
    }

    @Override
    public void paintLayerOnGraphics(Graphics2D g, boolean firstVisibleLayer) {
        throw new IllegalStateException("TODO");
    }

    @Override
    protected BufferedImage applyOnImage(BufferedImage src) {
        throw new IllegalStateException("TODO");
    }

    @Override
    public void setBlendingMode(BlendingMode newMode, boolean addToHistory, boolean update) {
        if (newMode == blendingMode) {
            return;
        }
        boolean wasPassThrough = isPassThrough();
        super.setBlendingMode(newMode, addToHistory, false);

        if (update) {
            boolean typeChange = wasPassThrough != isPassThrough();
            if (typeChange) {
                recalculateCachedImage();
                thumb = null;
                updateIconImage();
            }
            update();
        }
    }

    @Override
    public CompletableFuture<Void> resize(Dimension newSize) {
        // Do nothing for the layer group itself.
        // The mask and layers are resized via forEachNestedLayer.
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void crop(Rectangle2D cropRect, boolean deleteCropped, boolean allowGrowing) {
    }

    @Override
    public void flip(Flip.Direction direction) {
    }

    @Override
    public void rotate(QuadrantAngle angle) {
    }

    @Override
    public void enlargeCanvas(int north, int east, int south, int west) {
    }

    @Override
    public void forEachNestedLayer(Consumer<Layer> action, boolean includeMasks) {
        action.accept(this);
        if (includeMasks && hasMask()) {
            action.accept(getMask());
        }
        for (Layer layer : layers) {
            layer.forEachNestedLayer(action, includeMasks);
        }
    }

    @Override
    public String getTypeString() {
        return "Layer Group";
    }

    @Override
    public boolean contains(Layer target) {
        if (target == this) {
            return true;
        }
        for (Layer layer : layers) {
            if (layer.contains(target)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean containsClass(Class<? extends Layer> clazz) {
        if (getClass() == clazz) {
            return true;
        }
        for (Layer layer : layers) {
            if (layer.getClass() == clazz) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getActiveLayerIndex() {
        // TODO
        return layers.indexOf(comp.getActiveLayer());
    }

    @Override
    public int indexOf(Layer layer) {
        return layers.indexOf(layer);
    }

    @Override
    public int getNumLayers() {
        return layers.size();
    }

    @Override
    public Layer getLayer(int index) {
        return layers.get(index);
    }

    @Override
    public Stream<? extends Layer> levelStream() {
        return layers.stream();
    }

    @Override
    public void addLayerToList(int index, Layer newLayer) {
        layers.add(index, newLayer);
    }

    @Override
    public void changeLayerGUIOrder(int oldIndex, int newIndex) {
        updateChildrenUI();
    }

    @Override
    public void removeLayerFromList(Layer layer) {
        layers.remove(layer);
    }

    @Override
    public void deleteLayer(Layer layer, boolean addToHistory) {
        assert layer.getComp() == comp;
        assert layer.getHolder() == this;

        int layerIndex = layers.indexOf(layer);

        if (addToHistory) {
            History.add(new DeleteLayerEdit(this, layer, layerIndex));
        }

        layers.remove(layer);

        if (layer.isActive()) {
            if (layers.isEmpty()) {
                comp.setActiveLayer(this);
            } else if (layerIndex > 0) {
                comp.setActiveLayer(layers.get(layerIndex - 1));
            } else {  // deleted the fist layer, set the new first layer as active
                comp.setActiveLayer(layers.get(0));
            }
        }

        updateChildrenUI();
        update();
    }

    @Override
    public void deleteTemporarily(Layer layer) {
        layers.remove(layer);
    }

    @Override
    public void insertLayer(Layer layer, int index, boolean update) {
        if (update) {
            new Composition.LayerAdder(this).atIndex(index).add(layer);
        } else {
            layers.add(index, layer);
        }
    }

    @Override
    public void smartObjectChanged(boolean linked) {
        cachedImage = null;
        holder.smartObjectChanged(linked);
    }

    @Override
    public void updateIconImage() {
        thumb = null;
        super.updateIconImage();
    }

    @Override
    public boolean allowZeroLayers() {
        return true;
    }

    @Override
    public void replaceLayer(Layer before, Layer after) {
        boolean containedTarget = before.contains(comp.getActiveLayer());

        before.transferMaskAndUITo(after);

        int layerIndex = indexOf(before);
        assert layerIndex != -1;
        layers.set(layerIndex, after);

        if (containedTarget) {
            // TODO see comments in Composition.replaceLayer
            comp.setActiveLayer(after);
        }
        comp.checkInvariants();
    }

    @Override
    public BufferedImage createIconThumbnail() {
        if (isPassThrough()) {
            if (thumb == null) {
                thumb = ImageUtils.createCircleThumb(new Color(0, 138, 0));
            }
        } else {
            if (cachedImage != null) {
                thumb = createThumbnail(cachedImage, thumbSize, thumbCheckerBoardPainter);
            } else {
                thumb = ImageUtils.createCircleThumb(new Color(0, 0, 203));
            }
        }

        return thumb;
    }

    @Override
    public void unGroup() {
        replaceWithUnGrouped(null, true);
    }

    public void replaceWithUnGrouped(int[] prevIndices, boolean addToHistory) {
        int indexInParent = holder.indexOf(this);
        holder.deleteTemporarily(this);

        Layer activeBefore = comp.getActiveLayer();
        boolean activeWasThis = this == activeBefore;
        boolean activeWasInside = !activeWasThis && contains(activeBefore);

        int numLayers = layers.size();
        int[] insertIndices = new int[numLayers];
        for (int i = 0; i < numLayers; i++) {
            Layer layer = layers.get(i);

            // The owner field in LayerGUIs can cause problems later
            // because if the new holder is a Composition,
            // then it won't be automatically updated.
            layer.getUI().setOwner(null);

            int insertIndex = prevIndices != null ? prevIndices[i] : indexInParent;
            holder.insertLayer(layer, insertIndex, true);
            insertIndices[i] = insertIndex;
            indexInParent++;
        }

        if (activeWasInside) {
            // Inserting the layers into the parent will make
            // the last inserted layer active
            activeBefore.activate();
        }

        assert comp.getActiveRoot() != this;

        if (addToHistory) { // wasn't called from undo
            History.add(new GroupingEdit(holder, this, insertIndices, false));
        }
    }

    @Override
    public Rectangle getContentBounds() {
        return null;
    }

    @Override
    public int getPixelAtPoint(Point p) {
        return 0;
    }

    @Override
    PixelitorEdit createMovementEdit(int oldTx, int oldTy) {
        return null;
    }

    @Override
    public void setComp(Composition comp) {
        super.setComp(comp);

        for (Layer layer : layers) {
            layer.setComp(comp);
        }
    }

    @Override
    public LayerHolder getHolderForNewLayers() {
        return this;
    }

    @Override
    public boolean checkInvariants() {
        if (!super.checkInvariants()) {
            return false;
        }
        for (Layer layer : layers) {
            if (layer.getComp() != comp) {
                throw new AssertionError("bad comp in layer '%s' (that comp='%s', this='%s')".formatted(
                    layer.getName(), layer.getComp().getDebugName(), comp.getDebugName()));
            }
            if (layer.getHolder() != this) {
                throw new AssertionError("bad holder in layer '%s' (that holder='%s', this='%s')".formatted(
                    layer.getName(), layer.getHolder().getName(), getName()));
            }
            assert layer.checkInvariants();
        }
        return true;
    }

    @Override
    public String getORAStackXML() {
        return "<stack composite-op=\"%s\" name=\"%s\" opacity=\"%f\" visibility=\"%s\" isolation=\"%s\">\n".formatted(
            blendingMode.toSVGName(), getName(), getOpacity(), getVisibilityAsORAString(),
            blendingMode == BlendingMode.PASS_THROUGH ? "auto" : "isolate");
    }

    @Override
    public DebugNode createDebugNode(String key) {
        DebugNode node = super.createDebugNode(key);

        node.addBoolean("has cached image", cachedImage != null);
        node.addBoolean("has thumb", thumb != null);
        for (Layer layer : layers) {
            node.add(layer.createDebugNode());
        }

        return node;
    }
}


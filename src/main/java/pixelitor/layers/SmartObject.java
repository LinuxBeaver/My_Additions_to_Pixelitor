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
import pixelitor.Views;
import pixelitor.filters.Filter;
import pixelitor.filters.ParametrizedFilter;
import pixelitor.filters.gui.FilterState;
import pixelitor.gui.View;
import pixelitor.gui.utils.Dialogs;
import pixelitor.gui.utils.PAction;
import pixelitor.history.ContentLayerMoveEdit;
import pixelitor.io.FileChoosers;
import pixelitor.io.IO;
import pixelitor.utils.ImageUtils;
import pixelitor.utils.Messages;
import pixelitor.utils.Threads;
import pixelitor.utils.Utils;
import pixelitor.utils.debug.CompositionNode;
import pixelitor.utils.debug.Debug;
import pixelitor.utils.debug.DebugNode;

import javax.swing.*;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static java.awt.RenderingHints.*;
import static pixelitor.utils.Keys.CTRL_SHIFT_E;
import static pixelitor.utils.Threads.onEDT;

/**
 * A "smart object" that contains an embedded composition and allows "smart filters".
 * The cached result image behaves like the image of a regular {@link ImageLayer}.
 */
public class SmartObject extends ImageLayer {
    private static final String NAME_PREFIX = "smart ";
    private Composition content;
    private boolean smartFilterIsVisible = true;

    // only used for deserialization
    private final boolean newVersion = true;

    // null if the content is not linked
    // this is the same the content's file, but this one is not transient
    private File linkedContentFile;
    private transient long linkedContentFileTime;

    @Serial
    private static final long serialVersionUID = 8594248957749192719L;

    /**
     * Smart filters allow non-destructive editing, but unlike adjustment layers,
     * they don't have to run every time some other layer is edited.
     */
    private final List<Filter> smartFilters = new ArrayList<>();

    private AffineTransform contentTransform;

    // The following two fields are used only during editing of smart filters
    // to restore the image and filter state if the user cancels the dialog.
    private transient BufferedImage lastFilterOutput;
    private transient FilterState lastFilterState;

    private int indexOfLastSmartFilter = -1;

    private transient boolean imageNeedsRefresh = false;

    public SmartObject(Layer layer) {
        super(layer.getComp(), NAME_PREFIX + layer.getName());

        setContent(Composition.createEmpty(comp.getCanvasWidth(), comp.getCanvasHeight(), comp.getMode()));
        // the mask stays outside the content, and will become the mask of the smart object
        Layer contentLayer = layer.duplicate(true, false);
        contentLayer.setName("original content", false);
        contentLayer.setComp(content);
        content.addLayerInInitMode(contentLayer);
        content.setName(getName());
        content.createDebugName();
        copyBlendingFrom(layer);

        recalculateImage(false);
    }

    public SmartObject(Composition parent, Composition content) {
        super(parent, "Smart " + content.getName());
        setContent(content);
        recalculateImage(false);
    }

    // constructor called for "Add Linked"
    public SmartObject(File file, Composition parent, Composition content) {
        super(parent, file.getName());
        linkedContentFile = file;
        updateLinkedContentTime();
        setContent(content);
        recalculateImage(false);
    }

    // the constructor used for duplication
    private SmartObject(SmartObject orig, String name, boolean deepCopy) {
        super(orig.comp, name);
        if (deepCopy) {
            setContent(orig.content.copy(false, true));
        } else {
            setContent(orig.getContent());
        }
        image = orig.image;
        if (!orig.smartFilters.isEmpty()) {
            smartFilters.add(orig.smartFilters.get(0).copy());
        }
        lastFilterState = orig.lastFilterState;
        lastFilterOutput = orig.lastFilterOutput;
        indexOfLastSmartFilter = orig.indexOfLastSmartFilter;
        smartFilterIsVisible = orig.smartFilterIsVisible;
        linkedContentFile = orig.linkedContentFile;
        linkedContentFileTime = orig.linkedContentFileTime;
        if (orig.contentTransform != null) {
            contentTransform = new AffineTransform(orig.contentTransform);
        }
        forceTranslation(orig.getTx(), orig.getTy());
    }

    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();

        if (!newVersion) {
            // if the pxc was saved with an old version,
            // then assume smart filter visibility
            smartFilterIsVisible = true;
        }
        imageNeedsRefresh = true;
        lastFilterOutput = null;
        lastFilterState = null;
    }

    @Serial
    private void writeObject(ObjectOutputStream out) throws IOException {
        Composition tmp = content;
        if (isContentLinked()) {
            // if the content is linked, then don't write it
            content = null;
        }

        out.defaultWriteObject();

        content = tmp;
    }

    public void afterDeserialization() {
        for (Filter filter : smartFilters) {
            if (filter instanceof ParametrizedFilter pf) {
                pf.getParamSet().updateOptions(this, false);
            }
        }

        if (isContentLinked()) {
            if (linkedContentFile.exists()) {
                updateLinkedContentTime();
                // also read the content
                assert content == null;
                setContent(IO.loadCompSync(linkedContentFile));
            } else { // linked file not found
                // Set a transparent image as content to avoid all sorts of errors.
                // It will be replaced if the content is found later.
                setContent(Composition.createTransparent(
                    comp.getCanvasWidth(), comp.getCanvasHeight()));

                EventQueue.invokeLater(this::handleMissingContentLater);
            }
        } else {
            assert content != null;
            content.addOwner(this);
        }

        recalculateImage(false);
    }

    private void handleMissingContentLater() {
        assert Threads.calledOnEDT();

        String title = linkedContentFile.getName() + " not found.";
        String msg = "<html>The linked file <b>" + linkedContentFile.getAbsolutePath() +
                     "</b> was not found.<br>You can search for it or use a transparent image.";
        boolean search = Dialogs.showOKCancelDialog(msg, title,
            new String[]{"Search...", "Use Transparent Image"}, 0, JOptionPane.ERROR_MESSAGE);

        File newFile = null;
        if (search) {
            newFile = FileChoosers.getSupportedOpenFile();
        }
        if (newFile != null) { // file found
            linkedContentFile = newFile;
            updateLinkedContentTime();

            IO.loadCompAsync(linkedContentFile)
                .thenAcceptAsync(loadedComp -> {
                    setContent(loadedComp);
                    recalculateImage(true);
                    comp.update();
                }, onEDT);
        } else {
            // give up and use the previously created transparent image
            linkedContentFile = null;
        }
    }

    @Override
    boolean serializeImage() {
        return false;
    }

    private void recalculateImage(boolean updateIcon) {
        recalculateImage(comp.getCanvasWidth(), comp.getCanvasHeight(), updateIcon);
    }

    private void recalculateImage(int targetWidth, int targetHeight, boolean updateIcon) {
        resetImageFromContent(targetWidth, targetHeight);
        if (smartFilterIsVisible) {
            for (Filter filter : smartFilters) {
                image = filter.transformImage(image);
            }
        }
        if (updateIcon) {
            updateIconImage();
        }
        imageNeedsRefresh = false;
    }

    private void resetImageFromContent() {
        resetImageFromContent(comp.getCanvasWidth(), comp.getCanvasHeight());
    }

    private void resetImageFromContent(int targetWidth, int targetHeight) {
        image = content.getCompositeImage();
        boolean imageConverted = false;
        if (contentTransform != null) {
            BufferedImage newImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = newImage.createGraphics();
            g.setTransform(contentTransform);
            if (targetWidth > image.getWidth() || targetHeight > image.getHeight()) {
                g.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_BILINEAR);
            } else {
                g.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_BICUBIC);
            }
            g.drawImage(image, 0, 0, null);
            g.dispose();
            image = newImage;
            imageConverted = true;
        }
        if (!imageConverted && image.isAlphaPremultiplied()) {
            // image layer images should not be premultiplied
            image = ImageUtils.copyTo(BufferedImage.TYPE_INT_ARGB, image);
            imageConverted = true;
        }
        if (!imageConverted && ImageUtils.isSubImage(image)) {
            image = ImageUtils.copySubImage(image);
            imageConverted = true;
        }
    }

    public void invalidateImageCache() {
        imageNeedsRefresh = true;
    }

    /**
     * Ensures that all parents will reflect the changes in this
     * smart object when they are repainted.
     */
    public void propagateChanges(Composition content, boolean force) {
        // the reference might have been changed during editing
        setContent(content);

        if (!content.isDirty() && !force) {
            return;
        }

        invalidateImageCache();
        comp.smartObjectChanged(isContentLinked());
    }

    @Override
    public BufferedImage getVisibleImage() {
        if (imageNeedsRefresh) {
            recalculateImage(true);
        }
        return super.getVisibleImage();
    }

    @Override
    protected boolean isSmartObject() {
        return true;
    }

    @Override
    public boolean isRasterizable() {
        return true;
    }

    @Override
    protected String getRasterizedName() {
        return Utils.removePrefix(name, NAME_PREFIX);
    }

    @Override
    protected Layer createTypeSpecificDuplicate(String duplicateName) {
        return new SmartObject(this, duplicateName, true);
    }

    void addSmartObjectSpecificItems(JPopupMenu popup) {
        popup.add(new PAction("Edit Contents") {
            @Override
            protected void onClick() {
                edit();
            }
        });
        popup.add(new PAction("Clone") {
            @Override
            protected void onClick() {
                comp.shallowDuplicate(SmartObject.this);
            }
        });
        if (isContentLinked()) {
            popup.add(new PAction("Embed Contents") {
                @Override
                protected void onClick() {
                    embedLinkedContent();
                }
            });
            popup.add(new PAction("Reload Contents") {
                @Override
                protected void onClick() {
                    reloadLinkedContent();
                }
            });
        }
        boolean hasSmartFilter = !smartFilters.isEmpty();
        if (hasSmartFilter) {
            Filter smartFilter = smartFilters.get(0);
            popup.add(new PAction("Copy " + smartFilter.getName()) {
                @Override
                protected void onClick() {
                    Filter.copiedSmartFilter = smartFilter.copy();
                }
            });
        }
        if (Filter.copiedSmartFilter != null) {
            popup.add(new PAction("Paste " + Filter.copiedSmartFilter.getName()) {
                @Override
                protected void onClick() {
                    // copy again, because it could be pasted multiple times
                    pasteSmartFilter(Filter.copiedSmartFilter.copy());
                }
            });
        }
        if (hasSmartFilter) {
            popup.addSeparator();
            for (int i = 0; i < smartFilters.size(); i++) {
                int finalI = i;
                Filter smartFilter = smartFilters.get(i);
                PAction editAction = new PAction("Edit " + smartFilter.getName()) {
                    @Override
                    protected void onClick() {
                        editSmartFilter(finalI);
                    }
                };

                var editMenuItem = new JMenuItem(editAction);
                editMenuItem.setAccelerator(CTRL_SHIFT_E);
                popup.add(editMenuItem);

                popup.add(new PAction("Delete " + smartFilter.getName()) {
                    @Override
                    protected void onClick() {
                        deleteSmartFilter();
                    }
                });
            }
        }
    }

    @Override
    public void edit() {
        View contentView = content.getView();
        if (contentView == null) {
            Views.addAsNewComp(content);
            content.setDirty(false);
        } else {
            Views.activate(contentView);
        }
    }

    public void forAllNestedSmartObjects(Consumer<SmartObject> action) {
        assert checkInvariant();

        action.accept(this);
        content.forAllNestedSmartObjects(action);
    }

    public boolean hasSmartFilters() {
        return !smartFilters.isEmpty();
    }

    public boolean smartFilterIsVisible() {
        return smartFilterIsVisible;
    }

    public void setSmartFilterVisibility(boolean b) {
        boolean changed = smartFilterIsVisible != b;
        smartFilterIsVisible = b;
        if (changed) {
            recalculateImage(true);
            comp.update();
        }
    }

    public Filter getSmartFilter(int index) {
        return smartFilters.get(index);
    }

    public void addSmartFilter(Filter filter) {
        smartFilters.add(filter);
        smartFilterIsVisible = true;
        updateSmartFilterUI();
    }

    private void runAndAddSmartFilter(Filter newFilter) {
        assert smartFilters.isEmpty();

        lastFilterOutput = image;
        boolean filterDialogAccepted = startFilter(newFilter, false);
        if (filterDialogAccepted) {
            lastFilterOutput.flush();
            lastFilterOutput = null;
            addSmartFilter(newFilter);
        } else {
            image = lastFilterOutput;
        }
    }

    public void replaceSmartFilter(Filter newFilter) {
        Filter prevFilter = smartFilters.get(0);
        lastFilterOutput = image;
        smartFilters.clear();
        resetImageFromContent();

        // since this is a new filter instance, there is no need to reset it
        boolean filterDialogAccepted = startFilter(newFilter, false);
        if (filterDialogAccepted) {
            lastFilterOutput.flush();
            lastFilterOutput = null;
            addSmartFilter(newFilter);
        } else {
            image = lastFilterOutput;
            addSmartFilter(prevFilter);
        }
    }

    private void pasteSmartFilter(Filter newFilter) {
        if (hasSmartFilters()) {
            boolean replace = Dialogs.showReplaceSmartFilterQuestion(this, newFilter.getName());
            if (replace) {
                replaceSmartFilter(newFilter);
            }
        } else {
            runAndAddSmartFilter(newFilter);
        }
    }

    public void editSmartFilter(int index) {
        indexOfLastSmartFilter = index;
        Filter filter = smartFilters.get(index);
        lastFilterOutput = image;
        if (filter instanceof ParametrizedFilter pf) {
            lastFilterState = pf.getParamSet().copyState(false);
        }
        resetImageFromContent();
        boolean filterDialogAccepted = startFilter(filter, false);
        if (filterDialogAccepted) {
            // these are no longer needed
            lastFilterOutput.flush();
            lastFilterOutput = null;
            lastFilterState = null;
        } else {
            // restore to the result of the previous run
            image = lastFilterOutput;
            lastFilterOutput = null;
            // restore the previous filter state
            Filter sf = smartFilters.get(indexOfLastSmartFilter);
            if (sf instanceof ParametrizedFilter pf) {
                pf.getParamSet().setState(lastFilterState, false);
                lastFilterState = null;
            }
        }
        updateSmartFilterUI();
    }

    private void deleteSmartFilter() {
        smartFilters.clear();
        resetImageFromContent();
        comp.update();
        updateIconImage();
        updateSmartFilterUI();
    }

    private void updateSmartFilterUI() {
        ui.updateSmartFilterPanel();
        EventQueue.invokeLater(() ->
            comp.getView().getLayersPanel().revalidate());
    }

    @Override
    public BufferedImage getCanvasSizedSubImage() {
        // workaround for moved layers
        BufferedImage img = ImageUtils.createSysCompatibleImage(comp.getCanvas());
        Graphics2D g = img.createGraphics();

        // don't call applyLayer, because the mask should NOT be considered
        setupDrawingComposite(g, true);
        paintLayerOnGraphics(g, true);

        g.dispose();
        return img;
    }

    public View getParentView() {
        if (isContentOpen()) {
            return content.getView();
        }
        return comp.getParentView();
    }

    public CompletableFuture<Composition> checkForAutoReload() {
        assert !isContentOpen();
        assert checkInvariant();

        if (isContentLinked()) {
            long newLinkedContentFileTime = linkedContentFile.lastModified();
            if (newLinkedContentFileTime > linkedContentFileTime) {
                linkedContentFileTime = newLinkedContentFileTime;

                Views.activate(getParentView());
                boolean reload = Messages.reloadFileQuestion(linkedContentFile);
                if (reload) {
                    // if this content is reloaded, then return because
                    // the nested smart objects don't have to be checked
                    return reloadLinkedContent();
                }
            }
        }
        // also check recursively deeper
        return content.checkForAutoReload();
    }

    private CompletableFuture<Composition> reloadLinkedContent() {
        return reloadContent(linkedContentFile);
    }

    private CompletableFuture<Composition> reloadContent(File file) {
        return IO.loadCompAsync(file)
            .thenApplyAsync(loaded -> {
                setContent(loaded);
                recalculateImage(true);

                // only a grandparent composition might be opened
                propagateChanges(loaded, true);
                getParentView().repaint();
                return loaded;
            }, onEDT)
            .exceptionally(Messages::showExceptionOnEDT);
    }

    public Composition getContent() {
        assert checkInvariant();
        return content;
    }

    public void setContent(Composition content) {
        if (this.content != content) {
            this.content = content;
            content.addOwner(this);
        }
    }

    public boolean isContentOpen() {
        return content.isOpen();
    }

    public boolean isContentLinked() {
        return linkedContentFile != null;
    }

    public void setLinkedContentFile(File file) {
        this.linkedContentFile = file;
        updateLinkedContentTime();
    }

    private void updateLinkedContentTime() {
        linkedContentFileTime = linkedContentFile.lastModified();
    }

    private void embedLinkedContent() {
        String path = linkedContentFile.getAbsolutePath();
        linkedContentFile = null;
        Messages.showInfo("Embedded Content",
            "<html>The file <b>" + path + "</b> isn't used anymore.");
    }

    /**
     * Returns the composition that saves the contents of this smart object to its file
     */
    public Composition getSavingComp() {
        SmartObject so = this;
        while (true) {
            if (so.isContentLinked()) {
                return so.getContent();
            }
            Composition parent = so.getComp();
            if (parent.isSmartObjectContent()) {
                // assumes that all owners are in the same comp
                so = parent.getOwners().get(0);
            } else {
                return parent;
            }
        }
    }

    @Override
    public CompletableFuture<Void> resize(Dimension newSize) {
        double sx = newSize.getWidth() / comp.getCanvasWidth();
        double sy = newSize.getHeight() / comp.getCanvasHeight();

        setTranslation((int)(getTx() * sx), (int)(getTy() * sy));
        AffineTransform newScaling = AffineTransform.getScaleInstance(sx, sy);
        if (contentTransform == null) {
            contentTransform = newScaling;
        } else {
            contentTransform.concatenate(newScaling);
        }
        return CompletableFuture.runAsync(() ->
            recalculateImage(newSize.width, newSize.height, false));
    }

    @Override
    ContentLayerMoveEdit createMovementEdit(int oldTx, int oldTy) {
        // a smart object never enlarges the image
        return new ContentLayerMoveEdit(this, null, oldTx, oldTy);
    }

    @Override
    public void setTranslation(int x, int y) {
        // positive translation values are allowed for smart objects
        forceTranslation(x, y);
    }

    @Override
    public BufferedImage getCanvasSizedVisibleImage() {
        int tx = getTx();
        int ty = getTy();
        BufferedImage visibleImage = getVisibleImage();
        if (tx == 0 && ty == 0) {
            assert visibleImage.getWidth() == comp.getCanvasWidth();
            assert visibleImage.getHeight() == comp.getCanvasHeight();
            return visibleImage;
        }

        // the image of a moved layer might not cover the canvas,
        // therefore (unlike in the superclass) here we can't use subimage
        BufferedImage img = ImageUtils.createSysCompatibleImage(comp.getCanvas());
        Graphics2D g = img.createGraphics();
        g.drawImage(visibleImage, tx, ty, null);
        g.dispose();

        return img;
    }

    public SmartObject shallowDuplicate() {
        SmartObject duplicate = new SmartObject(this, getName() + " clone", false);
        return duplicate;
    }

    public boolean checkInvariant() {
        if (!content.isSmartObjectContent()) {
            throw new IllegalStateException("content of %s (%s) is broken"
                .formatted(getName(), content.getDebugName()));
        }
        return true;
    }

    @Override
    public String getTypeString() {
        return "Smart Object";
    }

    public void debugAllImages() {
        BufferedImage soImage = image;
        BufferedImage contentImage = content.getCompositeImage();
//        BufferedImage calcContentImage = content.calculateCompositeImage();

        Debug.debugImage(soImage, "soImage");
        Debug.debugImage(contentImage, "contentImage");
//        Debug.debugImage(calcContentImage, "calcContentImage");
    }

    @Override
    public DebugNode createDebugNode(String key) {
        DebugNode node = super.createDebugNode(key);

        boolean linked = isContentLinked();
        node.addBoolean("linked", linked);
        if (linked) {
            node.addString("link file", linkedContentFile.getAbsolutePath());
        }
        node.add(new CompositionNode("content", content));

        return node;
    }
}

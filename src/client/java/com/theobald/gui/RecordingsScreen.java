package com.theobald.gui;

import com.theobald.recording.Recordings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Path;

/** Lists saved recordings. Bottom-right button copies the selected one to the gallery. */
public class RecordingsScreen extends Screen {
    private final Screen parent;
    private RecordingList list;
    private ButtonWidget downloadButton;
    private ButtonWidget deleteButton;
    private volatile Text status = Text.empty();
    private volatile boolean busy = false;

    public RecordingsScreen(Screen parent) {
        super(Text.literal("Kayıtlar"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        list = new RecordingList(client, width, height, 32, height - 52, 28);
        list.reload();
        addSelectableChild(list);

        addDrawableChild(ButtonWidget.builder(Text.literal("Geri"), b -> close())
                .dimensions(10, height - 28, 80, 20).build());

        deleteButton = addDrawableChild(ButtonWidget.builder(Text.literal("Sil"), b -> deleteSelected())
                .dimensions(width / 2 - 40, height - 28, 80, 20).build());

        downloadButton = addDrawableChild(ButtonWidget.builder(Text.literal("İndir"), b -> downloadSelected())
                .dimensions(width - 110, height - 28, 100, 20).build());

        updateButtons();
    }

    private void updateButtons() {
        boolean selected = list != null && list.getSelectedOrNull() != null;
        downloadButton.active = selected && !busy;
        deleteButton.active = selected && !busy;
    }

    private void downloadSelected() {
        RecordingList.Item item = list.getSelectedOrNull();
        if (item == null || busy) return;
        Path source = item.entry.path();
        busy = true;
        status = Text.literal("Galeriye kaydediliyor...");
        updateButtons();
        Thread t = new Thread(() -> {
            try {
                Path target = Recordings.exportToGallery(source);
                String folder = target.getParent() != null && target.getParent().getFileName() != null
                        ? target.getParent().getFileName().toString() : "";
                status = Text.literal("Kaydedildi: " + target);
            } catch (IOException e) {
                status = Text.literal("İndirme başarısız: " + e.getMessage());
            } finally {
                busy = false;
            }
        }, "PvP Camera export");
        t.setDaemon(true);
        t.start();
    }

    private void deleteSelected() {
        RecordingList.Item item = list.getSelectedOrNull();
        if (item == null || busy) return;
        try {
            Recordings.delete(item.entry.path());
            status = Text.literal("Kayıt silindi");
        } catch (IOException e) {
            status = Text.literal("Silinemedi: " + e.getMessage());
        }
        list.reload();
        updateButtons();
    }

    @Override
    public void tick() {
        if (downloadButton != null) updateButtons();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        list.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        if (list.children().isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("Henüz kayıt yok"),
                    width / 2, height / 2 - 4, 0xAAAAAA);
        }
        context.drawCenteredTextWithShadow(textRenderer, status, width / 2, height - 44, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    private class RecordingList extends AlwaysSelectedEntryListWidget<RecordingList.Item> {
        RecordingList(MinecraftClient mc, int width, int height, int top, int bottom, int itemHeight) {
            super(mc, width, height, top, bottom, itemHeight);
        }

        void reload() {
            clearEntries();
            for (Recordings.Entry e : Recordings.list()) {
                addEntry(new Item(e));
            }
        }

        @Override
        public int getRowWidth() {
            return Math.min(width - 40, 360);
        }

        @Override
        protected int getScrollbarPositionX() {
            return width / 2 + getRowWidth() / 2 + 6;
        }

        class Item extends AlwaysSelectedEntryListWidget.Entry<Item> {
            final Recordings.Entry entry;

            Item(Recordings.Entry entry) {
                this.entry = entry;
            }

            @Override
            public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
                               int mouseX, int mouseY, boolean hovered, float tickDelta) {
                var tr = MinecraftClient.getInstance().textRenderer;
                context.drawTextWithShadow(tr, entry.title(), x + 4, y + 3, 0xFFFFFF);
                context.drawTextWithShadow(tr, entry.info(), x + 4, y + 15, 0xA0A0A0);
            }

            @Override
            public Text getNarration() {
                return Text.literal(entry.title());
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                RecordingList.this.setSelected(this);
                updateButtons();
                return true;
            }
        }
    }
}

package com.dyxiaojiazi.blockdetector.client;

import com.dyxiaojiazi.blockdetector.BlockDetectorClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class BlockDetectorConfigScreen extends Screen {
    private static final int NORMAL_TEXT = 0xE0E0E0;
    private static final int ERROR_TEXT = 0xFF5555;

    @Nullable
    private final Screen parent;

    private Button enabledButton;
    private Button throughBlocksButton;
    private Button saveButton;
    private Button startButton;
    private EditBox rangeBox;
    private EditBox intervalBox;
    private EditBox maxBlocksBox;
    private EditBox targetsBox;
    private EditBox redBox;
    private EditBox greenBox;
    private EditBox blueBox;
    private EditBox alphaBox;
    private boolean enabled;
    private boolean throughBlocks;
    private String errorMessage = "";

    public BlockDetectorConfigScreen(@Nullable Screen parent) {
        super(Component.translatable("screen.blockdetector.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int formWidth = Math.min(420, this.width - 40);
        int left = (this.width - formWidth) / 2;
        int labelX = left;
        int controlX = left + 150;
        int controlWidth = formWidth - 150;
        int y = Math.max(32, this.height / 2 - 118);

        this.enabled = BlockDetectorClientConfig.ENABLED.get();
        this.throughBlocks = BlockDetectorClientConfig.HIGHLIGHT_THROUGH_BLOCKS.get();

        this.enabledButton = this.addRenderableWidget(Button.builder(enabledMessage(), button -> {
            this.enabled = !this.enabled;
            button.setMessage(enabledMessage());
        }).bounds(controlX, y, controlWidth, 20).build());

        y += 28;
        this.rangeBox = addNumberBox(controlX, y, controlWidth, "scan_range", String.valueOf(BlockDetectorClientConfig.SCAN_RANGE.get()));
        y += 28;
        this.intervalBox = addNumberBox(controlX, y, controlWidth, "rescan_interval", String.valueOf(BlockDetectorClientConfig.RESCAN_INTERVAL_TICKS.get()));
        y += 28;
        this.maxBlocksBox = addNumberBox(controlX, y, controlWidth, "max_highlighted", String.valueOf(BlockDetectorClientConfig.MAX_HIGHLIGHTED_BLOCKS.get()));
        y += 28;

        this.throughBlocksButton = this.addRenderableWidget(Button.builder(throughBlocksMessage(), button -> {
            this.throughBlocks = !this.throughBlocks;
            button.setMessage(throughBlocksMessage());
        }).bounds(controlX, y, controlWidth, 20).build());

        y += 28;
        int colorBoxWidth = Math.max(35, (controlWidth - 18) / 4);
        this.redBox = addDecimalBox(controlX, y, colorBoxWidth, "red", BlockDetectorClientConfig.RED.get());
        this.greenBox = addDecimalBox(controlX + colorBoxWidth + 6, y, colorBoxWidth, "green", BlockDetectorClientConfig.GREEN.get());
        this.blueBox = addDecimalBox(controlX + (colorBoxWidth + 6) * 2, y, colorBoxWidth, "blue", BlockDetectorClientConfig.BLUE.get());
        this.alphaBox = addDecimalBox(controlX + (colorBoxWidth + 6) * 3, y, colorBoxWidth, "alpha", BlockDetectorClientConfig.ALPHA.get());

        y += 28;
        this.targetsBox = this.addRenderableWidget(new EditBox(
                this.font,
                controlX,
                y,
                controlWidth,
                20,
                Component.translatable("screen.blockdetector.config.targets")
        ));
        this.targetsBox.setMaxLength(4096);
        this.targetsBox.setValue(String.join(", ", BlockDetectorClientConfig.TARGET_BLOCKS.get()));
        this.targetsBox.setResponder(ignored -> validate());

        int buttonY = Math.min(this.height - 30, y + 36);
        int buttonWidth = Math.min(130, (formWidth - 16) / 3);
        this.startButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.blockdetector.config.start"), button -> startDetecting())
                .bounds(this.width / 2 - buttonWidth - 4 - (buttonWidth + 4) / 2, buttonY, buttonWidth, 20)
                .build());
        this.saveButton = this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> saveAndClose())
                .bounds(this.width / 2 - buttonWidth / 2, buttonY, buttonWidth, 20)
                .build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(this.width / 2 + buttonWidth / 2 + 4, buttonY, buttonWidth, 20)
                .build());

        validate();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        int formWidth = Math.min(420, this.width - 40);
        int left = (this.width - formWidth) / 2;
        int labelX = left;
        int y = Math.max(32, this.height / 2 - 118);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        drawLabel(graphics, labelX, y, "enabled");
        y += 28;
        drawLabel(graphics, labelX, y, "scan_range");
        y += 28;
        drawLabel(graphics, labelX, y, "rescan_interval");
        y += 28;
        drawLabel(graphics, labelX, y, "max_highlighted");
        y += 28;
        drawLabel(graphics, labelX, y, "through_blocks");
        y += 28;
        drawLabel(graphics, labelX, y, "color");
        y += 28;
        drawLabel(graphics, labelX, y, "targets");

        if (!this.errorMessage.isEmpty()) {
            graphics.drawCenteredString(this.font, this.errorMessage, this.width / 2, Math.min(this.height - 54, y + 28), ERROR_TEXT);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private EditBox addNumberBox(int x, int y, int width, String key, String value) {
        EditBox box = this.addRenderableWidget(new EditBox(this.font, x, y, width, 20, Component.translatable("screen.blockdetector.config." + key)));
        box.setMaxLength(8);
        box.setValue(value);
        box.setResponder(ignored -> validate());
        return box;
    }

    private EditBox addDecimalBox(int x, int y, int width, String key, double value) {
        EditBox box = this.addRenderableWidget(new EditBox(this.font, x, y, width, 20, Component.translatable("screen.blockdetector.config." + key)));
        box.setMaxLength(5);
        box.setValue(formatDecimal(value));
        box.setResponder(ignored -> validate());
        return box;
    }

    private void drawLabel(GuiGraphics graphics, int x, int y, String key) {
        graphics.drawString(this.font, Component.translatable("screen.blockdetector.config." + key), x, y + 6, NORMAL_TEXT);
    }

    private Component enabledMessage() {
        return Component.translatable(this.enabled ? "screen.blockdetector.config.enabled.on" : "screen.blockdetector.config.enabled.off");
    }

    private Component throughBlocksMessage() {
        return Component.translatable(this.throughBlocks ? "screen.blockdetector.config.through_blocks.on" : "screen.blockdetector.config.through_blocks.off");
    }

    private void validate() {
        this.errorMessage = "";
        boolean valid = true;

        valid &= validateInteger(this.rangeBox, 1, 1024);
        valid &= validateInteger(this.intervalBox, 1, 200);
        valid &= validateInteger(this.maxBlocksBox, 1, 8192);
        valid &= validateDecimal(this.redBox);
        valid &= validateDecimal(this.greenBox);
        valid &= validateDecimal(this.blueBox);
        valid &= validateDecimal(this.alphaBox);
        valid &= validateTargets();

        if (this.saveButton != null) {
            this.saveButton.active = valid;
        }
        if (this.startButton != null) {
            this.startButton.active = valid;
        }
    }

    private boolean validateInteger(EditBox box, int min, int max) {
        try {
            int value = Integer.parseInt(box.getValue().trim());
            boolean valid = value >= min && value <= max;
            box.setTextColor(valid ? EditBox.DEFAULT_TEXT_COLOR : ERROR_TEXT);
            if (!valid && this.errorMessage.isEmpty()) {
                this.errorMessage = Component.translatable("screen.blockdetector.config.error.number_range", min, max).getString();
            }
            return valid;
        } catch (NumberFormatException exception) {
            box.setTextColor(ERROR_TEXT);
            if (this.errorMessage.isEmpty()) {
                this.errorMessage = Component.translatable("screen.blockdetector.config.error.number").getString();
            }
            return false;
        }
    }

    private boolean validateDecimal(EditBox box) {
        try {
            double value = Double.parseDouble(box.getValue().trim());
            boolean valid = value >= 0.0D && value <= 1.0D;
            box.setTextColor(valid ? EditBox.DEFAULT_TEXT_COLOR : ERROR_TEXT);
            if (!valid && this.errorMessage.isEmpty()) {
                this.errorMessage = Component.translatable("screen.blockdetector.config.error.decimal_range").getString();
            }
            return valid;
        } catch (NumberFormatException exception) {
            box.setTextColor(ERROR_TEXT);
            if (this.errorMessage.isEmpty()) {
                this.errorMessage = Component.translatable("screen.blockdetector.config.error.number").getString();
            }
            return false;
        }
    }

    private boolean validateTargets() {
        for (String target : parseTargets()) {
            String id = target.startsWith("#") ? target.substring(1) : target;
            try {
                ResourceLocation.parse(id);
            } catch (RuntimeException exception) {
                this.targetsBox.setTextColor(ERROR_TEXT);
                if (this.errorMessage.isEmpty()) {
                    this.errorMessage = Component.translatable("screen.blockdetector.config.error.target", target).getString();
                }
                return false;
            }
        }

        this.targetsBox.setTextColor(EditBox.DEFAULT_TEXT_COLOR);
        return true;
    }

    private void saveAndClose() {
        saveSettings();
        ClientBlockHighlighter.forceRescan();
        onClose();
    }

    private void startDetecting() {
        saveSettings();
        ClientBlockHighlighter.startFromPlayer();
        onClose();
    }

    private void saveSettings() {
        List<String> targets = parseTargets();

        BlockDetectorClientConfig.ENABLED.set(this.enabled);
        BlockDetectorClientConfig.SCAN_RANGE.set(parseInt(this.rangeBox, 1, 1024));
        BlockDetectorClientConfig.RESCAN_INTERVAL_TICKS.set(parseInt(this.intervalBox, 1, 200));
        BlockDetectorClientConfig.MAX_HIGHLIGHTED_BLOCKS.set(parseInt(this.maxBlocksBox, 1, 8192));
        BlockDetectorClientConfig.HIGHLIGHT_THROUGH_BLOCKS.set(this.throughBlocks);
        BlockDetectorClientConfig.RED.set(parseDouble(this.redBox));
        BlockDetectorClientConfig.GREEN.set(parseDouble(this.greenBox));
        BlockDetectorClientConfig.BLUE.set(parseDouble(this.blueBox));
        BlockDetectorClientConfig.ALPHA.set(parseDouble(this.alphaBox));
        BlockDetectorClientConfig.TARGET_BLOCKS.set(targets);
        BlockDetectorClientConfig.SPEC.save();
    }

    private List<String> parseTargets() {
        String[] split = this.targetsBox.getValue().split("[,\\s]+");
        List<String> targets = new ArrayList<>();
        for (String raw : split) {
            String target = raw.trim();
            if (!target.isEmpty()) {
                targets.add(target);
            }
        }
        return targets;
    }

    private static int parseInt(EditBox box, int min, int max) {
        return Mth.clamp(Integer.parseInt(box.getValue().trim()), min, max);
    }

    private static double parseDouble(EditBox box) {
        return Mth.clamp(Double.parseDouble(box.getValue().trim()), 0.0D, 1.0D);
    }

    private static String formatDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}

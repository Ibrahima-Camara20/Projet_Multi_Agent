package fr.m2ia.mailmas.ui;

import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/** Barre de score du routing, avec un repère au seuil (sous lequel l'agent de repli prend la main). */
final class ScoreBar extends Pane {

    private final Region track = new Region();
    private final Region fill = new Region();
    private final Region tick = new Region();
    private final double score;
    private final double threshold;

    ScoreBar(double score, double threshold, String fillStyle) {
        this.score = Math.max(0, Math.min(1, score));
        this.threshold = threshold;
        track.getStyleClass().add("score-track");
        fill.getStyleClass().addAll("score-fill", fillStyle);
        tick.getStyleClass().add("score-tick");
        getChildren().addAll(track, fill, tick);
        setMinHeight(12);
        setPrefHeight(12);
        setMaxHeight(12);
        setAccessibleText("score " + Ui.score(score));
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        track.resizeRelocate(0, 3, w, 6);
        fill.resizeRelocate(0, 3, Math.round(w * score), 6);
        tick.resizeRelocate(Math.round(w * threshold), 0, 1, 12);
    }
}
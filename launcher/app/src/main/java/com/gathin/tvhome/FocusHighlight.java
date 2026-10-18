package com.gathin.tvhome;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Original floating-glass spring navigation. */
final class FocusHighlight implements ViewTreeObserver.OnPreDrawListener {
    private final ViewGroup root;
    private final GlassView glass;
    private final float density;
    private final SpringAnimation springX;
    private final SpringAnimation springY;
    private float targetX;
    private float targetY;
    private boolean visible;
    private boolean attached;
    private View card;
    private final Rect anchorBounds = new Rect();

    FocusHighlight(ViewGroup root, GlassView glass) {
        this.root = root;
        this.glass = glass;
        density = root.getResources().getDisplayMetrics().density;
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_Y);
        for (SpringAnimation spring : new SpringAnimation[]{springX, springY}) {
            spring.setSpring(new SpringForce().setStiffness(260f).setDampingRatio(0.58f));
        }
    }

    void resume() {
        if (!attached) {
            root.getViewTreeObserver().addOnPreDrawListener(this);
            attached = true;
        }
        springX.cancel();
        springY.cancel();
        visible = false;
        glass.animate().cancel();
        glass.setAlpha(0f);
    }

    void pause() {
        hide();
        if (attached && root.getViewTreeObserver().isAlive()) {
            root.getViewTreeObserver().removeOnPreDrawListener(this);
        }
        attached = false;
    }

    void focus(View card) {
        if (card == null || card.getWidth() == 0) return;
        this.card = card;
        Rect bounds = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, bounds);
        float inset = 6f * density;
        float width = card.getWidth() * AppAdapter.FOCUS_SCALE + 2f * inset;
        float height = card.getHeight() * AppAdapter.FOCUS_SCALE + 2f * inset;
        float x = bounds.exactCenterX() - width / 2f;
        float y = bounds.exactCenterY() - height / 2f;
        ViewGroup.LayoutParams params = glass.getLayoutParams();
        if (params.width != Math.round(width) || params.height != Math.round(height)) {
            params.width = Math.round(width);
            params.height = Math.round(height);
            glass.setLayoutParams(params);
            glass.setRadius(16f * density * AppAdapter.FOCUS_SCALE + inset);
        }
        targetX = x;
        targetY = y;
        if (!visible) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            glass.animate().alpha(1f).setDuration(180).start();
            visible = true;
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y);
        }
    }

    void scrolled(int dy) {
        if (dy != 0) syncAnchor();
    }

    @Override public boolean onPreDraw() {
        syncAnchor();
        return true;
    }

    private void syncAnchor() {
        if (!visible || card == null || !card.isAttachedToWindow() || !card.hasFocus()) return;
        anchorBounds.set(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, anchorBounds);
        float inset = 6f * density;
        float x = anchorBounds.exactCenterX() - (card.getWidth() * AppAdapter.FOCUS_SCALE + 2f * inset) / 2f;
        float y = anchorBounds.exactCenterY() - (card.getHeight() * AppAdapter.FOCUS_SCALE + 2f * inset) / 2f;
        // Focus can change during RecyclerView layout/scroll dispatch. The tile's bounds
        // may already include that scroll: subtracting dy again leaves the glass stranded.
        // Read the absolute destination, including layout-only changes after Home resumes.
        // Unchanged destinations leave the original springs completely untouched.
        if (Math.abs(x - targetX) > .01f) {
            targetX = x;
            if (springX.isRunning()) springX.animateToFinalPosition(x);
            else glass.setTranslationX(x);
        }
        if (Math.abs(y - targetY) > .01f) {
            targetY = y;
            if (springY.isRunning()) springY.animateToFinalPosition(y);
            else glass.setTranslationY(y);
        }
    }

    void hide() {
        card = null;
        springX.cancel();
        springY.cancel();
        if (!visible) return;
        visible = false;
        glass.animate().alpha(0f).setDuration(160).start();
    }

}

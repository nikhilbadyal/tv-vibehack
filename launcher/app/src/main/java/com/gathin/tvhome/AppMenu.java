package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import java.util.List;

/** Native, scrollable D-pad menus. No snapshots, blur, networking or worker threads. */
final class AppMenu {
    static final class Item {
        final String label;
        final Runnable action;
        final Boolean checked;
        Item(String label, Runnable action) { this(label, action, null); }
        Item(String label, Runnable action, Boolean checked) {
            this.label = label;
            this.action = action;
            this.checked = checked;
        }
    }

    static void show(Activity activity, View anchor, String title, List<Item> items) {
        if (items.isEmpty() || !anchor.isAttachedToWindow()) return;
        String[] labels = new String[items.size()];
        for (int i = 0; i < labels.length; i++) {
            Item item = items.get(i);
            labels[i] = Boolean.TRUE.equals(item.checked)
                    ? activity.getString(R.string.app_menu_checked, item.label) : item.label;
        }
        new AlertDialog.Builder(activity)
                .setTitle(title == null ? activity.getString(R.string.app_actions) : title)
                .setItems(labels, (dialog, position) -> {
                    dialog.dismiss();
                    items.get(position).action.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}

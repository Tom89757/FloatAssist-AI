package com.dshgojava.assist.action;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.CalendarContract;
import com.dshgojava.assist.AssistAction;
import com.dshgojava.assist.platform.NavUris;
import java.util.Locale;
import java.util.Objects;

/** Builds and launches the deliberately small set of action intents. */
public final class ActionIntents {
    public record Plan(
            AssistAction.Kind kind,
            String uri,
            CalendarDraft calendar,
            String copyText
    ) {
        public Plan {
            kind = Objects.requireNonNull(kind, "kind");
            uri = uri == null ? "" : uri;
            copyText = copyText == null ? "" : copyText;
        }
    }

    public enum Failure {
        NONE,
        INCOMPLETE,
        NO_HANDLER
    }

    private ActionIntents() { }

    /** Pure projection used by tests and by the Android dispatcher. */
    public static Plan plan(AssistAction action) {
        if (action == null) return null;
        String value = action.value() == null ? "" : action.value().strip();
        if (value.isEmpty()) return null;
        return switch (action.kind()) {
            case NAVIGATE -> {
                String uri = navigationUri(value);
                yield uri == null ? null : new Plan(action.kind(), uri, null, "");
            }
            case DIAL -> {
                String number = PhoneNumbers.normalize(value);
                yield number == null ? null : new Plan(action.kind(), "tel:" + number, null, "");
            }
            case CALENDAR -> {
                CalendarDraft draft = CalendarDraft.fromPayload(value);
                yield draft == null ? null : new Plan(action.kind(), "", draft, "");
            }
            case COPY -> new Plan(action.kind(), "", null, value);
        };
    }

    /** Must be called on the main thread; the caller owns copy handling for COPY. */
    public static Failure dispatch(Context context, AssistAction action) {
        Objects.requireNonNull(context, "context");
        Plan plan = plan(action);
        if (plan == null) return Failure.INCOMPLETE;
        if (plan.kind() == AssistAction.Kind.COPY) return Failure.INCOMPLETE;
        Intent intent = intentFor(plan);
        try {
            context.startActivity(intent);
            return Failure.NONE;
        } catch (ActivityNotFoundException | SecurityException error) {
            return Failure.NO_HANDLER;
        }
    }

    public static Intent intentFor(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        return switch (plan.kind()) {
            case NAVIGATE -> new Intent(Intent.ACTION_VIEW, Uri.parse(plan.uri()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            case DIAL -> new Intent(Intent.ACTION_DIAL, Uri.parse(plan.uri()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            case CALENDAR -> {
                CalendarDraft calendar = Objects.requireNonNull(plan.calendar(), "calendar");
                Intent intent = new Intent(Intent.ACTION_INSERT)
                        .setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, calendar.beginMs())
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, calendar.endMs())
                        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, calendar.allDay())
                        .putExtra(CalendarContract.Events.TITLE, calendar.title());
                if (!calendar.location().isBlank()) {
                    intent.putExtra(CalendarContract.Events.EVENT_LOCATION, calendar.location());
                }
                yield intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            case COPY -> throw new IllegalArgumentException("copy is handled by the card");
        };
    }

    private static String navigationUri(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("geo:") || lower.startsWith("http://")
                || lower.startsWith("https://") || lower.startsWith("amapuri://")
                || lower.startsWith("baidumap://")) {
            return value;
        }
        return NavUris.geo(value, null, null);
    }
}

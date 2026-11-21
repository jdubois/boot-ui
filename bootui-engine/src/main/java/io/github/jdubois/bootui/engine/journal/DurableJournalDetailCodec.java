package io.github.jdubois.bootui.engine.journal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class DurableJournalDetailCodec {

    private static final int MAGIC = 0x42554a44;

    private DurableJournalDetailCodec() {}

    static byte[] encode(DurableJournalDetail detail) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            data.writeInt(MAGIC);
            data.writeInt(detail.version());
            data.writeUTF(detail.kind().name());
            writeString(data, detail.exceptionGroupId());
            writeString(data, detail.exceptionClass());
            writeString(data, detail.signature());
            writeStrings(data, detail.types());
            writeStrings(data, detail.applicationFrames());
            writeStrings(data, detail.causes());
            data.writeLong(detail.occurrences());
            writeString(data, detail.requestMethod());
            writeString(data, detail.routeTemplate());
            writeString(data, detail.operation());
            data.writeBoolean(detail.status() != null);
            if (detail.status() != null) {
                data.writeInt(detail.status());
            }
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot encode durable journal detail", ex);
        }
    }

    static DurableJournalDetail decode(DurableJournalScope scope, long sequence, byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("Durable journal detail bytes must not be null");
        }
        try {
            DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes));
            if (data.readInt() != MAGIC) {
                throw new IllegalArgumentException("Invalid durable journal detail magic");
            }
            int version = data.readInt();
            DurableJournalDetail detail = new DurableJournalDetail(
                    scope,
                    sequence,
                    DurableJournalDetailKind.valueOf(data.readUTF()),
                    version,
                    readString(data),
                    readString(data),
                    readString(data),
                    readStrings(data),
                    readStrings(data),
                    readStrings(data),
                    data.readLong(),
                    readString(data),
                    readString(data),
                    readString(data),
                    data.readBoolean() ? data.readInt() : null);
            if (data.available() != 0) {
                throw new IllegalArgumentException("Trailing durable journal detail data");
            }
            return detail;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Malformed durable journal detail", ex);
        }
    }

    private static void writeStrings(DataOutputStream data, List<String> values) throws IOException {
        data.writeInt(values.size());
        for (String value : values) {
            writeString(data, value);
        }
    }

    private static List<String> readStrings(DataInputStream data) throws IOException {
        int count = data.readInt();
        if (count < 0 || count > DurableJournalDetail.MAX_ITEMS) {
            throw new IllegalArgumentException("Invalid durable journal detail item count: " + count);
        }
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(readString(data));
        }
        return List.copyOf(values);
    }

    private static void writeString(DataOutputStream data, String value) throws IOException {
        data.writeBoolean(value != null);
        if (value != null) {
            data.writeInt(value.length());
            data.writeChars(value);
        }
    }

    private static String readString(DataInputStream data) throws IOException {
        if (!data.readBoolean()) {
            return null;
        }
        int length = data.readInt();
        if (length < 0 || length > DurableJournalDetail.MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Invalid durable journal detail string length: " + length);
        }
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(data.readChar());
        }
        return value.toString();
    }
}

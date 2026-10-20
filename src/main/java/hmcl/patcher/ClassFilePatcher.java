package hmcl.patcher;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Replaces the body of `RegionUtils.isIsChinaMainlandNetwork()` with `return false;`.
///
/// The editor works directly on the raw class file: the constant pool and every other member are
/// copied byte for byte, only the `Code` attribute of the target method is replaced by
/// `iconst_0; ireturn`. Because the original body is never inspected, the same patch works for
/// every HMCL build that ships this method, no matter how the body is written.
public final class ClassFilePatcher {

    /// Jar entry name of the class holding the region based download switch.
    public static final String REGION_UTILS_ENTRY = "org/jackhuang/hmcl/util/RegionUtils.class";

    /// Replaced method.
    private static final String TARGET_METHOD = "isIsChinaMainlandNetwork";

    /// Descriptor of the replaced method: `boolean method()`.
    private static final String TARGET_DESCRIPTOR = "()Z";

    private static final String CODE_ATTRIBUTE = "Code";

    /// Payload of the replacement `Code` attribute:
    /// max_stack=1, max_locals=1, code_length=2, `iconst_0; ireturn`,
    /// empty exception table, no attributes.
    private static final byte[] STUB = {
            0, 1,                   // max_stack
            0, 1,                   // max_locals
            0, 0, 0, 2,             // code_length
            0x03, (byte) 0xAC,      // iconst_0, ireturn
            0, 0,                   // exception_table_length
            0, 0                    // attributes_count
    };

    /// State of the target method inside a class file.
    public enum State {
        /// The class does not declare the target method, so the build has no download restriction.
        ABSENT,
        /// The method still contains the upstream implementation.
        ORIGINAL,
        /// The method was already replaced by this tool.
        PATCHED
    }

    private ClassFilePatcher() {
    }

    /// Checks the state of the target method inside the given class file.
    public static State inspect(byte[] classFile) throws PatchException {
        MethodLayout layout = parse(classFile);
        if (layout == null) {
            return State.ABSENT;
        }
        return layout.code != null && Arrays.equals(layout.code, STUB)
                ? State.PATCHED
                : State.ORIGINAL;
    }

    /// Returns a copy of the given class file whose target method always returns `false`.
    public static byte[] patch(byte[] classFile) throws PatchException {
        MethodLayout layout = parse(classFile);
        if (layout == null) {
            throw new PatchException("类文件中找不到方法 " + TARGET_METHOD + TARGET_DESCRIPTOR);
        }
        if (layout.codeNameIndex < 0) {
            throw new PatchException("方法 " + TARGET_METHOD + TARGET_DESCRIPTOR + " 没有 Code 属性");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(classFile.length + 16);
        out.write(classFile, 0, layout.attributeCountPos);
        writeU2(out, layout.attributes.size());
        for (Attribute attribute : layout.attributes) {
            if (attribute.nameIndex == layout.codeNameIndex) {
                writeU2(out, attribute.nameIndex);
                writeU4(out, STUB.length);
                out.write(STUB, 0, STUB.length);
            } else {
                out.write(classFile, attribute.start, attribute.end - attribute.start);
            }
        }
        out.write(classFile, layout.end, classFile.length - layout.end);
        return out.toByteArray();
    }

    /// Byte range of one method attribute.
    private record Attribute(int nameIndex, int start, int end) {
    }

    /// Everything needed to rewrite the target method in place.
    private static final class MethodLayout {
        /// Offset of the `attributes_count` field of the target method.
        int attributeCountPos;
        /// Offset directly behind the target method.
        int end;
        /// Attributes of the target method, in declaration order.
        List<Attribute> attributes = new ArrayList<>();
        /// Constant pool index of the `Code` UTF-8 entry, or `-1` if the method has no code.
        int codeNameIndex = -1;
        /// Raw payload of the `Code` attribute, or `null` if the method has no code.
        byte[] code;
    }

    private static MethodLayout parse(byte[] classFile) throws PatchException {
        try {
            return parse0(classFile);
        } catch (IndexOutOfBoundsException e) {
            throw new PatchException("class 文件被截断或已损坏", e);
        }
    }

    private static MethodLayout parse0(byte[] classFile) throws PatchException {
        if (classFile.length < 10 || readInt(classFile, 0) != 0xCAFEBABE) {
            throw new PatchException("不是有效的 Java class 文件");
        }

        int[] pos = {8}; // magic(4) + minor(2) + major(2)
        int constantPoolCount = readU2(classFile, pos);
        String[] utf8 = new String[constantPoolCount];

        for (int i = 1; i < constantPoolCount; i++) {
            int tag = classFile[pos[0]++] & 0xFF;
            switch (tag) {
                case 1 -> {
                    int length = readU2(classFile, pos);
                    utf8[i] = new String(classFile, pos[0], length, StandardCharsets.UTF_8);
                    pos[0] += length;
                }
                case 3, 4 -> pos[0] += 4;                     // Integer, Float
                case 5, 6 -> {                                // Long, Double
                    pos[0] += 8;
                    i++;
                }
                case 7, 8, 16, 19, 20 -> pos[0] += 2;         // Class, String, MethodType, Module, Package
                case 9, 10, 11, 12, 17, 18 -> pos[0] += 4;    // refs, NameAndType, Dynamic, InvokeDynamic
                case 15 -> pos[0] += 3;                       // MethodHandle
                default -> throw new PatchException("未知的常量池条目类型: " + tag);
            }
        }

        pos[0] += 6;                                          // access_flags, this_class, super_class
        int interfaceCount = readU2(classFile, pos);
        pos[0] += 2 * interfaceCount;
        skipMembers(classFile, pos);                          // fields

        int methodCount = readU2(classFile, pos);
        for (int i = 0; i < methodCount; i++) {
            pos[0] += 2;                                      // access_flags
            int nameIndex = readU2(classFile, pos);
            int descriptorIndex = readU2(classFile, pos);
            int attributeCount = readU2(classFile, pos);
            int attributeCountPos = pos[0] - 2;

            boolean target = TARGET_METHOD.equals(utf8[nameIndex])
                    && TARGET_DESCRIPTOR.equals(utf8[descriptorIndex]);

            MethodLayout layout = target ? new MethodLayout() : null;
            if (layout != null) {
                layout.attributeCountPos = attributeCountPos;
            }

            for (int a = 0; a < attributeCount; a++) {
                int start = pos[0];
                int attributeNameIndex = readU2(classFile, pos);
                int length = readU4(classFile, pos);
                int payloadStart = pos[0];
                pos[0] += length;

                if (layout != null) {
                    layout.attributes.add(new Attribute(attributeNameIndex, start, pos[0]));
                    if (CODE_ATTRIBUTE.equals(utf8[attributeNameIndex])) {
                        layout.codeNameIndex = attributeNameIndex;
                        layout.code = Arrays.copyOfRange(classFile, payloadStart, pos[0]);
                    }
                }
            }

            if (layout != null) {
                layout.end = pos[0];
                return layout;
            }
        }
        return null;
    }

    private static void skipMembers(byte[] classFile, int[] pos) {
        int count = readU2(classFile, pos);
        for (int i = 0; i < count; i++) {
            pos[0] += 6;                                      // access_flags, name_index, descriptor_index
            int attributeCount = readU2(classFile, pos);
            for (int a = 0; a < attributeCount; a++) {
                pos[0] += 2;                                  // attribute_name_index
                int length = readU4(classFile, pos);          // attribute_length
                pos[0] += length;                             // payload
            }
        }
    }

    private static int readU2(byte[] data, int[] pos) {
        int value = ((data[pos[0]] & 0xFF) << 8) | (data[pos[0] + 1] & 0xFF);
        pos[0] += 2;
        return value;
    }

    private static int readU4(byte[] data, int[] pos) {
        int value = readInt(data, pos[0]);
        pos[0] += 4;
        return value;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static void writeU2(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeU4(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}

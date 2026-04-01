package org.omri.radio.impl;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import org.omri.radioservice.metadata.Textual;

public abstract class TextualImpl implements Textual, Serializable {
    private String mFullText = "";

    public static class EBUChar {
        static final char[][] EBU_SET = {
            new char[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            new char[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            new char[]{' ', '!', '\"', '#', 164, '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/'},
            new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ':', ';', '<', '=', '>', '?'},
            new char[]{'@', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O'},
            new char[]{'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '[', '\\', ']', 8213, '_'},
            new char[]{9553, 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o'},
            new char[]{'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', '{', '|', '}', 175, 0},
            new char[]{225, 224, 233, 232, 237, 236, 243, 242, 250, 249, 209, 199, 350, 223, 161, 306},
            new char[]{226, 228, 234, 235, 238, 239, 244, 246, 251, 252, 241, 231, 351, 287, 305, 307},
            new char[]{170, 945, 169, 8240, 286, 283, 328, 337, 960, 8364, 163, '$', 8592, 8593, 8594, 8595},
            new char[]{186, 185, 178, 179, 177, 304, 324, 369, 181, 191, 247, 176, 188, 189, 190, 167},
            new char[]{193, 192, 201, 200, 205, 204, 211, 210, 218, 217, 344, 268, 352, 381, 208, 319},
            new char[]{194, 196, 202, 203, 206, 207, 212, 214, 219, 220, 345, 269, 353, 382, 273, 320},
            new char[]{195, 197, 198, 338, 375, 221, 213, 216, 222, 330, 340, 262, 346, 377, 358, 240},
            new char[]{227, 229, 230, 339, 373, 253, 245, 248, 254, 331, 341, 263, 347, 378, 359, 0}
        };

        public static byte getCel(byte b6) {
            return (byte) (b6 & 15);
        }

        public static byte getRow(byte b6) {
            return (byte) (((byte) (b6 >> 4)) & 15);
        }
    }

    @Override
    public String getText() { return this.mFullText; }

    public void setText(String str) { this.mFullText = str; }

    public void setTextBytes(byte[] bArr, int i6) {
        try {
            if (i6 != 0) {
                if (i6 != 4) {
                    return;
                }
                this.mFullText = new String(bArr, 0, bArr.length, "ISO-8859-1");
                return;
            }
            for (byte b6 : bArr) {
                this.mFullText += EBUChar.EBU_SET[EBUChar.getRow(b6)][EBUChar.getCel(b6)];
            }
        } catch (UnsupportedEncodingException unused) {
        }
    }
}

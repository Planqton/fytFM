package at.planqton.fytfm.rds;

/**
 * RDS Basic Code Table (G0) Decoder nach EBU/IEC 62106 Annex E.1.
 *
 * Wandelt Bytes wie sie der FM-Chip emittiert in einen Java-/Unicode-String.
 * Die G0-Tabelle deckt die in Europa üblichen Zeichen ab — Umlaute,
 * Akzente, Sonderzeichen wie €, ©, ‰ etc.
 *
 * Konkret löst das den Bug, dass der Chip z.B. {@code Ö} als Byte
 * {@code 0xD7} schickt — in Latin-1 wäre das {@code ×} (multiplication
 * sign), in G0 ist es korrekt {@code Ö}.
 *
 * Bytes 0x00–0x1F (Control Codes) werden auf Space gemappt; sie werden
 * sowieso später von cleanRdsString() entfernt.
 */
public final class RdsCharset {

    private static final char[] G0 = new char[256];
    static {
        // 0x00–0x1F: Control codes → Space (cleanRdsString stripped sie eh weg).
        for (int i = 0; i < 0x20; i++) G0[i] = ' ';
        // 0x20–0x7E: ASCII identisch.
        for (int i = 0x20; i < 0x7F; i++) G0[i] = (char) i;
        G0[0x7F] = ' '; // DEL → Space

        // 0x80–0x8F
        G0[0x80] = 'á'; G0[0x81] = 'à'; G0[0x82] = 'é'; G0[0x83] = 'è';
        G0[0x84] = 'í'; G0[0x85] = 'ì'; G0[0x86] = 'ó'; G0[0x87] = 'ò';
        G0[0x88] = 'ú'; G0[0x89] = 'ù'; G0[0x8A] = 'Ñ'; G0[0x8B] = 'Ç';
        G0[0x8C] = 'Ş'; G0[0x8D] = 'β'; G0[0x8E] = '¡'; G0[0x8F] = 'Ĳ';

        // 0x90–0x9F
        G0[0x90] = 'â'; G0[0x91] = 'ä'; G0[0x92] = 'ê'; G0[0x93] = 'ë';
        G0[0x94] = 'î'; G0[0x95] = 'ï'; G0[0x96] = 'ô'; G0[0x97] = 'ö';
        G0[0x98] = 'û'; G0[0x99] = 'ü'; G0[0x9A] = 'ñ'; G0[0x9B] = 'ç';
        G0[0x9C] = 'ş'; G0[0x9D] = 'ǧ'; G0[0x9E] = 'ı'; G0[0x9F] = 'ĳ';

        // 0xA0–0xAF
        G0[0xA0] = 'ª'; G0[0xA1] = 'α'; G0[0xA2] = '©'; G0[0xA3] = '‰';
        G0[0xA4] = 'Ǧ'; G0[0xA5] = 'ě'; G0[0xA6] = 'ň'; G0[0xA7] = 'ő';
        G0[0xA8] = 'π'; G0[0xA9] = '€'; G0[0xAA] = '£'; G0[0xAB] = '$';
        G0[0xAC] = '←'; G0[0xAD] = '↑'; G0[0xAE] = '→'; G0[0xAF] = '↓';

        // 0xB0–0xBF
        G0[0xB0] = 'º'; G0[0xB1] = '¹'; G0[0xB2] = '²'; G0[0xB3] = '³';
        G0[0xB4] = '±'; G0[0xB5] = 'İ'; G0[0xB6] = 'ń'; G0[0xB7] = 'ű';
        G0[0xB8] = 'µ'; G0[0xB9] = '¿'; G0[0xBA] = '÷'; G0[0xBB] = '°';
        G0[0xBC] = '¼'; G0[0xBD] = '½'; G0[0xBE] = '¾'; G0[0xBF] = '§';

        // 0xC0–0xCF (Capitals mit Akut/Grave)
        G0[0xC0] = 'Á'; G0[0xC1] = 'À'; G0[0xC2] = 'É'; G0[0xC3] = 'È';
        G0[0xC4] = 'Í'; G0[0xC5] = 'Ì'; G0[0xC6] = 'Ó'; G0[0xC7] = 'Ò';
        G0[0xC8] = 'Ú'; G0[0xC9] = 'Ù'; G0[0xCA] = 'Ř'; G0[0xCB] = 'Č';
        G0[0xCC] = 'Š'; G0[0xCD] = 'Ž'; G0[0xCE] = 'Ð'; G0[0xCF] = 'Ŀ';

        // 0xD0–0xDF (Capitals mit Zirkumflex/Trema)
        G0[0xD0] = 'Â'; G0[0xD1] = 'Ä'; G0[0xD2] = 'Ê'; G0[0xD3] = 'Ë';
        G0[0xD4] = 'Î'; G0[0xD5] = 'Ï'; G0[0xD6] = 'Ô'; G0[0xD7] = 'Ö';
        G0[0xD8] = 'Û'; G0[0xD9] = 'Ü'; G0[0xDA] = 'ř'; G0[0xDB] = 'č';
        G0[0xDC] = 'š'; G0[0xDD] = 'ž'; G0[0xDE] = 'đ'; G0[0xDF] = 'ŀ';

        // 0xE0–0xEF
        G0[0xE0] = 'Ã'; G0[0xE1] = 'Å'; G0[0xE2] = 'Æ'; G0[0xE3] = 'Œ';
        G0[0xE4] = 'ŷ'; G0[0xE5] = 'Ý'; G0[0xE6] = 'Õ'; G0[0xE7] = 'Ø';
        G0[0xE8] = 'Þ'; G0[0xE9] = 'Ŋ'; G0[0xEA] = 'Ŕ'; G0[0xEB] = 'Ć';
        G0[0xEC] = 'Ś'; G0[0xED] = 'Ź'; G0[0xEE] = 'Ŧ'; G0[0xEF] = 'ð';

        // 0xF0–0xFF
        G0[0xF0] = 'ã'; G0[0xF1] = 'å'; G0[0xF2] = 'æ'; G0[0xF3] = 'œ';
        G0[0xF4] = 'ŵ'; G0[0xF5] = 'ý'; G0[0xF6] = 'õ'; G0[0xF7] = 'ø';
        G0[0xF8] = 'þ'; G0[0xF9] = 'ŋ'; G0[0xFA] = 'ŕ'; G0[0xFB] = 'ć';
        G0[0xFC] = 'ś'; G0[0xFD] = 'ź'; G0[0xFE] = 'ŧ'; G0[0xFF] = ' ';
    }

    private RdsCharset() {}

    /** Dekodiert einen RDS-G0-Byte-Stream nach Unicode. */
    public static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            sb.append(G0[b & 0xFF]);
        }
        return sb.toString();
    }
}

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * C6461 Two-Pass Assembler
 *
 * - Supports: LOC, Data, Labels, Comments (;)
 * - Encodes many instruction formats described in the C6461 ISA.
 * - Produces a listing file (octal address, octal word, original source) and a load file.
 *
 * Notes: The ISA in your PDF uses octal opcodes in the tables (e.g., 01, 70, 31...). We parse those octal
 * values and use their integer equivalents in the 6-bit opcode field.
 *
 * This assembler focuses on assembling instruction bit patterns. It does not simulate runtime effects.
 */
public class Assembler {

    // Symbol table: label -> address (decimal)
    private final Map<String, Integer> symtab = new HashMap<>();

    // Intermediate representation for lines to be encoded in pass2
    private static class SrcLine {
        int lineNo;
        int address;         // address assigned in pass1 (decimal)
        String label;        // may be null
        String op;           // opcode or directive
        String operands;     // operands text (may be null)
        String comment;      // comment text (may be null)
        String rawLine;      // original raw input for listing
        boolean generatesWord; // true if it consumes a memory word (Data or instruction)
        Integer dataValue;   // for Data directive with numeric immediate (resolved in pass1 or pass2)
        String dataLabelRef; // for Data directive referencing a label (resolve in pass2)
        public SrcLine(int ln, String raw) { this.lineNo=ln; this.rawLine=raw; }
    }
    private final List<SrcLine> srcLines = new ArrayList<>();

    // Opcode mapping: mnemonic -> opcode integer (from octal as given in ISA)
    private static final Map<String, Integer> OPCODES = new HashMap<>();
    static {
        // Misc
        OPCODES.put("HLT", oct("00"));
        OPCODES.put("TRAP", oct("30"));

        // Load/Store
        OPCODES.put("LDR", oct("01"));
        OPCODES.put("STR", oct("02"));
        OPCODES.put("LDA", oct("03"));
        OPCODES.put("LDX", oct("41"));
        OPCODES.put("STX", oct("42"));

        // Transfer (Jumps)
        OPCODES.put("JZ", oct("10"));
        OPCODES.put("JNE", oct("11"));
        OPCODES.put("JCC", oct("12"));
        OPCODES.put("JMA", oct("13"));
        OPCODES.put("JSR", oct("14"));
        OPCODES.put("RFS", oct("15"));
        OPCODES.put("SOB", oct("16"));
        OPCODES.put("JGE", oct("17"));

        // Arithmetic/Logical (memory forms)
        OPCODES.put("AMR", oct("04"));
        OPCODES.put("SMR", oct("05"));
        OPCODES.put("AIR", oct("06"));
        OPCODES.put("SIR", oct("07"));

        // Register-register and multiplies (octal 70..75)
        OPCODES.put("MLT", oct("70"));
        OPCODES.put("DVD", oct("71"));
        OPCODES.put("TRR", oct("72"));
        OPCODES.put("AND", oct("73")); // NOTE ISA lists AND/ORR/NOT but names in table: AND, ORR, NOT
        OPCODES.put("ORR", oct("74"));
        OPCODES.put("NOT", oct("75"));

        // Shift/Rotate
        OPCODES.put("SRC", oct("31"));
        OPCODES.put("RRC", oct("32"));

        // I/O
        OPCODES.put("IN", oct("61"));
        OPCODES.put("OUT", oct("62"));
        OPCODES.put("CHK", oct("63"));

        // Floating/Vector (encoded similarly to load/store)
        OPCODES.put("FADD", oct("33"));
        OPCODES.put("FSUB", oct("34"));
        OPCODES.put("VADD", oct("35"));
        OPCODES.put("VSUB", oct("36"));
        OPCODES.put("CNVRT", oct("37"));
        OPCODES.put("LDFR", oct("50"));
        OPCODES.put("STFR", oct("51"));
    }

    // helper to parse octal literals from strings like "70"
    private static int oct(String s) {
        return Integer.parseInt(s, 8);
    }

    // regex helpers
    private static final Pattern LABEL_PATTERN = Pattern.compile("^\\s*([A-Za-z_]\\w*):\\s*(.*)$");
    private static final Pattern LOC_PATTERN = Pattern.compile("^LOC\\s+(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_PATTERN = Pattern.compile("^Data\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    // Main entry
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java Assembler <input.asm> <listing.txt> <loadfile.txt>");
            System.exit(1);
        }
        Path input = Paths.get(args[0]);
        Path listingOut = Paths.get(args[1]);
        Path loadOut = Paths.get(args[2]);

        Assembler asm = new Assembler();
        asm.assemble(input, listingOut, loadOut);
        System.out.println("Assembling complete. Listing -> " + listingOut + ", Load -> " + loadOut);
    }

    // Orchestrates two-pass assembly
    public void assemble(Path srcFile, Path listingFile, Path loadFile) throws IOException {
        List<String> lines = Files.readAllLines(srcFile);
        pass1(lines);
        pass2AndWrite(listingFile, loadFile);
    }

    // PASS 1: parse lines, assign addresses, build symbol table
    private void pass1(List<String> lines) {
        int loc = 0;
        int lineno = 0;
        for (String raw : lines) {
            lineno++;
            String line = raw;
            String comment = null;
            int semi = line.indexOf(';');
            if (semi >= 0) {
                comment = line.substring(semi+1).trim();
                line = line.substring(0, semi);
            }
            if (line.trim().isEmpty() && (comment==null || comment.isEmpty())) {
                // store blank line for listing but no generated word
                SrcLine sl = new SrcLine(lineno, raw);
                sl.generatesWord = false;
                sl.rawLine = raw;
                srcLines.add(sl);
                continue;
            }

            // check label at start
            String label = null;
            Matcher labm = LABEL_PATTERN.matcher(line);
            if (labm.matches()) {
                label = labm.group(1);
                line = labm.group(2);
            }

            line = line.trim();
            if (line.isEmpty()) {
                // label only line
                SrcLine sl = new SrcLine(lineno, raw);
                sl.label = label;
                sl.address = loc;
                sl.generatesWord = false;
                srcLines.add(sl);
                if (label != null) {
                    if (symtab.containsKey(label)) {
                        System.err.println("Warning: label redefined: " + label + " at line " + lineno);
                    }
                    symtab.put(label, loc);
                }
                continue;
            }

            // split first token (op) and rest
            String[] parts = line.split("\\s+", 2);
            String op = parts[0];
            String operands = (parts.length>1)? parts[1].trim(): null;

            // handle LOC directive (argument in decimal)
            Matcher locm = LOC_PATTERN.matcher(line);
            if (locm.matches()) {
                int newLoc = Integer.parseInt(locm.group(1)); // decimal as in spec
                loc = newLoc;
                // record a source line for LOC for listing but it does not allocate memory
                SrcLine sl = new SrcLine(lineno, raw);
                sl.op = "LOC";
                sl.operands = locm.group(1);
                sl.address = loc;
                sl.rawLine = raw;
                sl.generatesWord = false;
                srcLines.add(sl);
                continue;
            }

            // handle Data directive - it consumes a memory word
            Matcher datm = DATA_PATTERN.matcher(line);
            if (datm.matches()) {
                String arg = datm.group(1).trim();
                SrcLine sl = new SrcLine(lineno, raw);
                sl.label = label;
                sl.op = "Data";
                sl.operands = arg;
                sl.address = loc;
                sl.generatesWord = true;
                // if arg is an integer
                if (isInteger(arg)) {
                    sl.dataValue = Integer.parseInt(arg); // decimal value as spec
                } else {
                    sl.dataLabelRef = arg;
                }
                srcLines.add(sl);
                if (label != null) symtab.put(label, loc);
                loc++; // consume word
                continue;
            }

            // normal instruction line
            SrcLine sl = new SrcLine(lineno, raw);
            sl.label = label;
            sl.op = op.toUpperCase();
            sl.operands = operands;
            sl.address = loc;
            sl.generatesWord = true; // instructions consume one word
            srcLines.add(sl);
            if (label != null) {
                if (symtab.containsKey(label)) {
                    System.err.println("Warning: label redefined: " + label + " at line " + lineno);
                }
                symtab.put(label, loc);
            }
            loc++;
        }
    }

    // PASS 2: encode and write output
    private void pass2AndWrite(Path listingFile, Path loadFile) throws IOException {
        List<String> listingLines = new ArrayList<>();
        List<String> loadLines = new ArrayList<>();

        for (SrcLine sl : srcLines) {
            if (!sl.generatesWord) {
                // For LOC or blank lines or label-only lines, we still want the listing copy to include the raw line
                listingLines.add(String.format("        %s", sl.rawLine));
                continue;
            }

            int address = sl.address;
            int word = 0;

            if ("Data".equalsIgnoreCase(sl.op)) {
                // Data directive: either numeric value or label reference
                if (sl.dataValue != null) {
                    word = sl.dataValue & 0xFFFF;
                } else {
                    // resolve label
                    if (sl.dataLabelRef == null) {
                        error("Data directive missing value at line " + sl.lineNo);
                    }
                    String lab = sl.dataLabelRef;
                    if (!symtab.containsKey(lab)) {
                        error("Undefined label '" + lab + "' used in Data at line " + sl.lineNo);
                    }
                    word = symtab.get(lab) & 0xFFFF;
                }
            } else {
                // Instruction encoding
                String op = sl.op.toUpperCase();
                if (!OPCODES.containsKey(op)) {
                    error("Unknown opcode '" + op + "' at line " + sl.lineNo + ": " + sl.rawLine);
                }
                int opcode = OPCODES.get(op) & 0x3F; // 6 bits
                // depending on opcode class we parse operands differently
                if (isLoadStoreOrTransfer(op)) {
                    // format: op R, IX, ADDRESS[,I]
                    // example: LDR 3,0,15  or LDR 1,2,10,1
                    ParsedLoadStore p = parseLoadStoreOperands(sl.operands, sl.lineNo);
                    int r = p.r & 0x3;
                    int ix = p.ix & 0x3;
                    int i = p.i?1:0;
                    int addrField = resolveAddressField(p.addrExpr, sl.lineNo); // 5-bit field
                    word = (opcode << 10) | (r << 8) | (ix << 6) | (i << 5) | (addrField & 0x1F);
                } else if (isArithmeticImmediate(op)) {
                    // AIR/SIR: format AIR r, immed  (IX and I ignored)
                    int[] rr = parseRegAndImmediate(sl.operands, sl.lineNo);
                    int r = rr[0];
                    int immed = rr[1];
                    if (immed < 0 || immed > 31) { error("Immediate out of range (0..31) at line " + sl.lineNo); }
                    word = (opcode << 10) | (r << 8) | (0 << 6) | (0 << 5) | (immed & 0x1F);
                } else if (isRegisterToRegister(op)) {
                    // format: opcode (6) rx (2) ry (2) rest ignored
                    int[] regs = parseTwoRegs(sl.operands, sl.lineNo);
                    int rx = regs[0] & 0x3;
                    int ry = regs[1] & 0x3;
                    // place rx in R field (bits 9..8), ry in IX field (bits 7..6)
                    word = (opcode << 10) | (rx << 8) | (ry << 6);
                } else if (isShiftRotate(op)) {
                    // SRC r, count, L/R, A/L
                    // count 0..15 (4 bits). L/R and A/L are 0 or 1.
                    ShiftSpec ss = parseShiftSpec(sl.operands, sl.lineNo);
                    int r = ss.r & 0x3;
                    int count = ss.count & 0xF;
                    int lr = ss.lr?1:0;
                    int al = ss.al?1:0;
                    // place: opcode(6) r(2) count(4) L/R(1) A/L(1) rest zeros
                    word = (opcode << 10) | (r << 8) | (count << 4) | (lr << 3) | (al << 2);
                } else if (isIO(op)) {
                    // IN r, devid  -> opcode(6) r(2) devid(5)
                    int[] rr = parseRegAndDev(sl.operands, sl.lineNo);
                    int r = rr[0] & 0x3;
                    int devid = rr[1] & 0x1F;
                    word = (opcode << 10) | (r << 8) | (devid & 0x1F);
                } else if ("HLT".equals(op)) {
                    word = (opcode << 10); // rest zero
                } else {
                    // default: try to encode as load/store style (many FP and vector ops use same format)
                    ParsedLoadStore p = parseLoadStoreOperands(sl.operands, sl.lineNo);
                    int r = p.r & 0x3;
                    int ix = p.ix & 0x3;
                    int i = p.i?1:0;
                    int addrField = resolveAddressField(p.addrExpr, sl.lineNo);
                    word = (opcode << 10) | (r << 8) | (ix << 6) | (i << 5) | (addrField & 0x1F);
                }
            }

            String addrOct = toOctal6(address);
            String wordOct = toOctal6(word & 0xFFFF);
            listingLines.add(addrOct + " " + wordOct + " " + sl.rawLine);
            loadLines.add(addrOct + " " + wordOct);
        }

        Files.write(listingFile, listingLines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.write(loadFile, loadLines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // Helpers, parsing and encoding utilities

    private static boolean isInteger(String s) {
        try { Integer.parseInt(s); return true; } catch(Exception e) { return false; }
    }

    private void error(String msg) {
        System.err.println("ERROR: " + msg);
        System.exit(2);
    }

    private static String toOctal6(int v) {
        // represent as 6 octal digits (leading zeros), matching examples (e.g., 000012)
        v = v & 0xFFFF;
        String o = Integer.toOctalString(v);
        while (o.length() < 6) o = "0" + o;
        return o;
    }

    // Determine whether op uses load/store style fields
    private boolean isLoadStoreOrTransfer(String op) {
        return Arrays.asList("LDR","STR","LDA","LDX","STX",
                "JZ","JNE","JCC","JMA","JSR","RFS","SOB","JGE",
                "FADD","FSUB","VADD","VSUB","CNVRT","LDFR","STFR").contains(op);
    }

    private boolean isArithmeticImmediate(String op) {
        return Arrays.asList("AIR","SIR").contains(op);
    }

    private boolean isRegisterToRegister(String op) {
        return Arrays.asList("MLT","DVD","TRR","AND","ORR","NOT").contains(op);
    }

    private boolean isShiftRotate(String op) {
        return Arrays.asList("SRC","RRC").contains(op);
    }

    private boolean isIO(String op) {
        return Arrays.asList("IN","OUT","CHK").contains(op);
    }

    // Parse operands for load/store/transfer: "r,ix,addr[,I]" or for LDX: "x,addr[,I]" (we will allow missing r for LDX)
    private static class ParsedLoadStore {
        int r;
        int ix;
        boolean i;
        String addrExpr; // either numeric or label
    }
    private ParsedLoadStore parseLoadStoreOperands(String opers, int lineno) {
        ParsedLoadStore p = new ParsedLoadStore();
        if (opers == null) {
            error("Missing operands for load/store-style instruction at line " + lineno);
        }
        // remove spaces around commas
        String s = opers.replaceAll("\\s*,\\s*", ",").trim();
        String[] toks = s.split(",");
        // three-token form r,ix,addr (or r,ix,addr,I)
        if (toks.length == 3 || toks.length == 4) {
            // r, ix, addr [,I]
            p.r = parseRegisterToken(toks[0], lineno);
            p.ix = parseIndexToken(toks[1], lineno);
            p.i = false;
            p.addrExpr = toks[2];
            if (toks.length == 4) {
                String t = toks[3].trim();
                if (t.equalsIgnoreCase("1") || t.equalsIgnoreCase("I")) p.i = true;
                else if (t.equalsIgnoreCase("0")) p.i = false;
                else error("Invalid 4th field for load/store (expect I or 1) at line " + lineno + ": " + t);
            }
            return p;
        }
        // two-token form: for LDX x,addr[,I]
        if (toks.length == 2 || toks.length == 3) {
            p.r = 0; // not used
            p.ix = parseIndexToken(toks[0], lineno); // e.g., "2" for X2 in LDX
            p.addrExpr = toks[1];
            p.i = false;
            if (toks.length == 3) {
                String t = toks[2].trim();
                if (t.equalsIgnoreCase("1") || t.equalsIgnoreCase("I")) p.i = true;
            }
            return p;
        }
        error("Cannot parse operands for load/store-style instruction at line " + lineno + ": " + opers);
        return null;
    }

    private int parseRegisterToken(String t, int lineno) {
        if (t == null) error("Missing register token at line " + lineno);
        t = t.trim();
        try {
            int r = Integer.parseInt(t);
            if (r < 0 || r > 3) error("Register out of range 0..3 at line " + lineno + ": " + t);
            return r;
        } catch (NumberFormatException e) {
            error("Invalid register token at line " + lineno + ": " + t);
            return 0;
        }
    }
    private int parseIndexToken(String t, int lineno) {
        if (t == null) error("Missing IX token at line " + lineno);
        t = t.trim();
        // IX fields are 0 (no indexing) or 1..3
        try {
            int ix = Integer.parseInt(t);
            if (ix < 0 || ix > 3) error("Index register out of range 0..3 at line " + lineno + ": " + t);
            return ix;
        } catch (NumberFormatException e) {
            error("Invalid IX token at line " + lineno + ": " + t);
            return 0;
        }
    }

    // Resolve address expression for address field (5 bits)
    // The assembler leaves it to the programmer to use index registers when necessary.
    // We simply take the value (label or numeric) and emit the lower 5 bits (0..31). If the value doesn't fit
    // into 5 bits, we still encode low bits but warn user (typical use uses indexing to extend addressing).
    private int resolveAddressField(String expr, int lineno) {
        if (expr == null) error("Missing address expression at line " + lineno);
        expr = expr.trim();
        int val;
        if (isInteger(expr)) {
            val = Integer.parseInt(expr); // decimal per spec
        } else {
            // a label: look it up
            if (!symtab.containsKey(expr)) {
                error("Undefined label '" + expr + "' at line " + lineno);
            }
            val = symtab.get(expr);
        }
        if (val < 0) error("Negative address at line " + lineno);
        if (val > 0xFFFF) System.err.println("Warning: address > 16 bits at line " + lineno);
        if (val > 31) {
            // addresses greater than 31 require indexing; assembler will still encode the low 5 bits,
            // but programmer must ensure IX is set such that c(IX)+addr = desired EA.
            System.err.println("Warning: address " + val + " > 31; encoding lower 5 bits ("
                    + (val & 0x1F) + "). Use indexing registers to reach full address. (line " + lineno + ")");
        }
        return val & 0x1F;
    }

    // parse "r, immed"
    private int[] parseRegAndImmediate(String operands, int lineno) {
        if (operands == null) error("Missing operands at line " + lineno);
        String s = operands.replaceAll("\\s*,\\s*", ",").trim();
        String[] toks = s.split(",");
        if (toks.length < 2) error("Expected 2 operands (reg, immed) at line " + lineno);
        int r = parseRegisterToken(toks[0], lineno);
        String immS = toks[1].trim();
        if (!isInteger(immS)) error("Immediate must be decimal integer at line " + lineno);
        int imm = Integer.parseInt(immS);
        return new int[]{r, imm};
    }

    // parse "r, devid" for IN/OUT/CHK
    private int[] parseRegAndDev(String operands, int lineno) {
        if (operands == null) error("Missing operands at line " + lineno);
        String s = operands.replaceAll("\\s*,\\s*", ",").trim();
        String[] toks = s.split(",");
        if (toks.length < 2) error("Expected 2 operands (reg, devid) at line " + lineno);
        int r = parseRegisterToken(toks[0], lineno);
        int devid;
        try {
            devid = Integer.parseInt(toks[1]);
        } catch (NumberFormatException e) { error("Device id must be decimal at line " + lineno); return null; }
        if (devid < 0 || devid > 31) error("Device id out of range 0..31 at line " + lineno);
        return new int[]{r, devid};
    }

    // parse two registers "rx, ry"
    private int[] parseTwoRegs(String operands, int lineno) {
        if (operands == null) error("Missing registers at line " + lineno);
        String s = operands.replaceAll("\\s*,\\s*", ",").trim();
        String[] toks = s.split(",");
        if (toks.length < 2) error("Expected two registers (rx, ry) at line " + lineno);
        int rx = parseRegisterToken(toks[0], lineno);
        int ry = parseRegisterToken(toks[1], lineno);
        return new int[]{rx, ry};
    }

    // parse shift spec "r, count, L/R, A/L"
    private static class ShiftSpec { int r; int count; boolean lr; boolean al; }
    private ShiftSpec parseShiftSpec(String operands, int lineno) {
        if (operands == null) error("Missing operands for shift/rotate at line " + lineno);
        String s = operands.replaceAll("\\s*,\\s*", ",").trim();
        String[] toks = s.split(",");
        if (toks.length < 4) error("Expected 4 operands (r,count,L/R,A/L) at line " + lineno);
        ShiftSpec ss = new ShiftSpec();
        ss.r = parseRegisterToken(toks[0], lineno);
        ss.count = Integer.parseInt(toks[1].trim());
        if (ss.count < 0 || ss.count > 15) error("Shift count out of range 0..15 at line " + lineno);
        String lr = toks[2].trim();
        String al = toks[3].trim();
        ss.lr = (lr.equals("1") || lr.equalsIgnoreCase("L") || lr.equalsIgnoreCase("Left") || lr.equalsIgnoreCase("l"));
        // interpret L/R: we'll assume 1 => left, 0 => right (ISA says L/R = 1 means left)
        if (!ss.lr && !(lr.equals("0") || lr.equalsIgnoreCase("R") || lr.equalsIgnoreCase("Right") || lr.equalsIgnoreCase("r"))) {
            // allow "1" or "L" or "Left"; otherwise assume 0
        }
        ss.al = (al.equals("1") || al.equalsIgnoreCase("A") || al.equalsIgnoreCase("Arithmetic") || al.equalsIgnoreCase("a"));
        return ss;
    }
}

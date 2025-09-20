# C6461 Two-Pass Assembler - Project Package

**Files included in this package** (all are in this folder):

- `Assembler.java` : Java source code for the two-pass assembler
- `program_A.asm` : Large sample assembly program (Program A)
- `program_A_expected_listing.txt` : The expected assembler listing (octal address, octal word, original source)
- `program_A_expected_loadfile.txt` : The expected load file produced by the assembler (address + octal word)
- `program_B.asm` : Second sample assembly program (Program B) using branching/labels
- `program_B_expected_listing.txt` : Expected listing for Program B
- `program_B_expected_loadfile.txt` : Expected load file for Program B
- `OPCODES_REFERENCE.md` : Opcode map (octal -> decimal) and brief notes
- `design_document.md` : Design document with object model, interfaces, module responsibilities
- `README.md` : This file (you are reading it)
- `HOW_TO_TEST.md` : Step-by-step testing and expected outputs
- `compile_and_run.sh` : Helper script to compile Assembler.java and assemble sample programs (UNIX)

---

## Project Overview (long form)

This project contains a **two-pass assembler** implementation written in Java that targets the C6461-style 16-bit instruction format described by the course. The assembler reads an assembly source file (text) and produces:

1. A **listing**: Each instruction/data word printed with its octal address, the encoded machine word in octal, and the original source line (for human-readable debugging).
2. A **load file**: A compact file of address + encoded word (in octal) suitable for loading into a simulator or grading harness.

**Important assembler behaviors**:

- Two-pass assembly: pass 1 builds a symbol table and assigns addresses; pass 2 encodes words using the resolved symbols.
- Directives supported: `LOC <decimal>` (sets location counter), `Data <value>` (allocates a data word), labels (`LABEL:`), and comments starting with `;`.
- Instruction formats: The assembler recognizes many C6461 instruction classes: memory-reference (LDR/STR/LDA/AMR/SMR/AIR/SIR/etc), register-register (MLT/DVD/TRR/AND/ORR/NOT), shift/rotate (SRC/RRC), IO (IN/OUT/CHK), floating/vector forms (FADD/FSUB/VADD/VSUB etc.).

---

## How to compile (detailed)

**Requirements**: Java JDK 1.8 or later, `javac` and `java` in your PATH.

1. Open a terminal/command prompt and change directory to this folder.

2. Compile the Java source:

```bash
javac Assembler.java
```

If compilation succeeds, `Assembler.class` will be generated.

3. Run the assembler on a sample program:

```bash
java Assembler program_A.asm program_A_listing.txt program_A_loadfile.txt
```

This creates `program_A_listing.txt` and `program_A_loadfile.txt` in this directory. Compare them to the expected files shipped in this folder.

---

## Running the shipped test suite (UNIX)

We included a convenience script `compile_and_run.sh`:

```bash
chmod +x compile_and_run.sh
./compile_and_run.sh
```

On Windows, run the `javac` and `java` commands from a command prompt as shown above.

---

## Assembler input syntax (detailed)

- `LOC <decimal>`: set the location counter to a decimal address (example: `LOC 100`).
- `LABEL:`: labels that appear at the beginning of a line must be followed by a colon and optionally an instruction or directive after the colon. The label will be assigned the current LC value (address).
- `Data <value or label>`: reserve a single word. If the operand is an integer (decimal), that numeric value is placed in the word. If it is a label, the assembler resolves that label to its address and emits that address value.
- `;` begins a comment that extends to end of line.

**Instruction operands** (typical forms):

- Memory-reference: `OP R,IX,ADDR[,I]` (e.g., `LDR 3,0,15` or `LDR 1,2,10,1`)
- LDX/STX form: `LDX x,ADDR[,I]` (two-token form)
- Arithmetic immediate: `AIR R,IMMED` (IMMED 0..31)
- Register-register: `MLT rx, ry`
- Shift/rotate: `SRC r, count, L/R, A/L` (count 0..15; L/R and A/L are flags)
- IO: `OUT r, devid` (devid 0..31)
- `HLT` has no operands

---

## Example: assembling `program_A.asm` (expected output explanation)

After compiling and running `java Assembler program_A.asm program_A_listing.txt program_A_loadfile.txt`, you should see output that matches `program_A_expected_listing.txt` and `program_A_expected_loadfile.txt`.

Each line of the listing file is formatted as:

```
AAAAAA WWWWWW <original source line>
```

where `AAAAAA` is the 6-digit octal address, and `WWWWWW` is the 6-digit octal representation of the 16-bit machine word.

The load file contains only the `AAAAAA WWWWWW` pairs (one per line) intended for loading into a simulator.

---

## Extending the assembler

- Add opcodes: modify the `OPCODES` static map inside `Assembler.java` (entries are octal-coded strings converted to decimal at class load time).
- Add new directives: implement pass1 parsing and pass2 code generation for new pseudo-ops.
- Add more robust expression parsing: currently numeric operands are decimal integers only; you may add support for octal or hex constants.

---

## Troubleshooting

- `Undefined label` error: check that the label is spelled exactly the same and that it appears somewhere in the assembled file. The assembler performs two-pass linking and supports forward references, but labels must exist.
- `Register out of range 0..3`: ensure register fields are 0..3 for the R and IX fields.
- `Immediate out of range`: AIR/SIR immediate values must fit into 5 bits (0..31).

---

## HOW TO TEST THE ASSEMBLER (DETAILED)

1. Compile the Java source

   - Open terminal in this folder and run:
     ```bash
     javac Assembler.java
     ```

2. Assemble Program A (included)

   - Run:
     ```bash
     java Assembler program_A.asm program_A_listing.txt program_A_loadfile.txt
     ```
   - Compare produced files to the expected files shipped with this package:
     - program_A_expected_listing.txt
     - program_A_expected_loadfile.txt
   - You can diff or visually compare them:
     ```bash
     diff program_A_listing.txt program_A_expected_listing.txt
     diff program_A_loadfile.txt program_A_expected_loadfile.txt
     ```

3. Assemble Program B (included)

   - Run:
     ```bash
     java Assembler program_B.asm program_B_listing.txt program_B_loadfile.txt
     ```
   - Compare with expected outputs:
     ```bash
     diff program_B_listing.txt program_B_expected_listing.txt
     diff program_B_loadfile.txt program_B_expected_loadfile.txt
     ```

4. Inspect outputs

   - The listing file lines look like:
     ```
     AAAAAA WWWWWW <original source line>
     ```
     where AAAAAA is the octal address and WWWWWW is the octal machine word.

5. If an error occurs

   - Check exact line number printed in the error message and inspect the corresponding line in the `.asm` file for syntax/operand issues.
   - Typical errors: "Undefined label" (label misspelled or missing), "Register out of range" (R/IX must be 0..3), "Immediate out of range" (AIR/SIR immed must be 0..31).

6. Modifying and re-testing
   - Edit `program_A.asm` or `program_B.asm` or create new `.asm` files to test more instructions.
   - Re-run:
     ```bash
     java Assembler <file>.asm <listing>.txt <loadfile>.txt
     ```

---

# Design Document - C6461 Two-Pass Assembler

## High level architecture

The assembler is implemented as a single Java class `Assembler` that performs two passes over the input file and writes two outputs: a human-readable listing and a load file. The Java implementation organizes responsibilities into methods that power the two-pass algorithm.

### Major components (in code)

- `symtab` (Map<String,Integer>): symbol table mapping labels to addresses
- `SrcLine` (inner class): stores parsed information for each source input line (raw text, op, operands, address, flags)
- `OPCODES` (static map): mapping from mnemonic to opcode integer (read from octal-coded strings)
- `pass1(List<String> lines)`: first pass parser building `srcLines` list and `symtab`
- `pass2AndWrite(Path listingFile, Path loadFile)`: resolves addresses and encodes each `SrcLine` to a 16-bit word, writes outputs

### Encoding and Instruction Formats

- Word encoding (memory-reference style): `opcode (6bits) | R (2bits) | IX (2bits) | I (1bit) | ADDRESS (5bits)`
- Register-to-register instructions place rx in R field and ry in IX field.
- Arithmetic immediate instructions (AIR/SIR): `opcode | R | immediate (5 bits)` placed in low 5 bits.
- Shift/Rotate and IO instructions follow specialized bit placements described in the source code.

## Interfaces and I/O

- `public void assemble(Path srcFile, Path listingFile, Path loadFile)` - main entry used by `main`.
- Program input: plain text `.asm` file with directives and instructions.
- Program outputs: `listing.txt` (readable listing) and `loadfile.txt` (address/machine-word pairs).

## Data flow

1. Read all lines from the source assembly file into memory for processing.
2. pass1:
   - For each line: Remove comments and whitespace, Identify and record labels, Handle `LOC` directives, `Data` directives, and normal instructions.
   - Assign addresses using a location counter `loc` controlled by `LOC` directives.
   - For `Data` directives with numeric arguments, record values; for label references, record unresolved references.
3. pass2:
   - For each `SrcLine` that generates a word: encode a 16-bit integer using opcode + parsed operands.
   - Use `symtab` for label resolution during address and data encodings.
4. Write listing and load files.

## Extensibility points

- Add opcode table entries in `OPCODES` map.
- Add new instruction classes by adding new `isXYZ(op)` predicate and the corresponding encode branch.
- Support additional numeric literal formats (octal/hex) by enhancing `isInteger` and parsing helpers.

## Error handling

- The assembler prints clear textual error messages and exits on fatal errors (missing label, invalid operand, value out-of-range).
- Nonfatal issues (like label redefinition) emit warnings but still allow assembly to continue.

## Testing strategy

- Unit tests can be made by providing `.asm` files and comparing produced output files to expected text files (already included for Program A and B).
- Edge cases: forward references, labels at the same line as instruction, label-only lines, LOC adjustments, Data referencing labels.

---

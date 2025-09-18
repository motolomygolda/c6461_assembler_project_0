#!/bin/bash
# Compile and run the assembler on the provided sample programs
javac Assembler.java
if [ $? -ne 0 ]; then
  echo "Compilation failed"
  exit 1
fi
java Assembler program_A.asm program_A_listing.txt program_A_loadfile.txt
java Assembler program_B.asm program_B_listing.txt program_B_loadfile.txt
echo "Done. Check program_A_listing.txt and program_A_loadfile.txt etc."
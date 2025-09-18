; Program B - demonstrates branches, LDX, JZ, JNE, OUT, label references and Data referencing labels
; Contains forward references where branch target labels appear later in the program

LOC 40
ENTRY:  LDR 0,0,60         ; Load status from data area
        JZ 0,0,80         ; If zero -> jump to HANDLER (label at 80)
        AIR 0,1           ; increment R0 by 1
        JNE 0,0,90        ; if not equal -> jump to DONE at 90
        HLT

LOC 60
STATUS: Data 0

LOC 80
HANDLER: LDR 1,0,60
         OUT 1,4          ; output device 4 using reg 1
         HLT

LOC 90
DONE:   HLT

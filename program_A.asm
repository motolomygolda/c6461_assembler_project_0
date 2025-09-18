; Program A - simple arithmetic/data operations (C6461 style)
; Assembles at decimal addresses and demonstrates LDR, AMR, AIR, MLT, STR, HLT and Data

LOC 10
START:  LDR 0,0,100        ; R0 <- M[100]
        LDR 1,0,101        ; R1 <- M[101]
        AMR 0,0,102        ; R0 <- R0 + M[102]
        AIR 1,5            ; R1 <- R1 + 5
        MLT 0,1            ; R0 <- R0 * R1  (register-to-register multiply; uses two regs)
        STR 0,0,103        ; M[103] <- R0
        HLT                

; Data region
LOC 100
D1: Data 7
D2: Data 3
D3: Data 2
D4: Data 0

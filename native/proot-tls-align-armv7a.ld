/* Linker script to align TLS segment to 32 bytes for ARM32 (armeabi-v7a) */
SECTIONS
{
  /* Standard sections first */
  . = ALIGN(16384);
  .text : {
    *(.text.unlikely .text.*_unlikely .text.unlikely.*)
    *(.text.exit .text.exit.*)
    *(.text.startup .text.startup.*)
    *(.text.hot .text.hot.*)
    *(SORT(.text.sorted.*))
    *(.text .stub .text.* .gnu.linkonce.t.*)
    /* .gnu.warning sections are handled specially by elf.em.  */
    *(.gnu.warning)
    /* .gnu.warning sections are handled specially by elf.em.  */
    *(.glue_7t) *(.glue_7) *(.vfp11_veneer) *(.v4_bx)
  }
  .fini : { KEEP (*(SORT_NONE(.fini))) }
  PROVIDE (__etext = .);
  PROVIDE (_etext = .);
  PROVIDE (etext = .);
  .rodata         : { *(.rodata .rodata.* .gnu.linkonce.r.*) }
  .rodata1        : { *(.rodata1) }
  .ARM.extab   : { *(.ARM.extab* .gnu.linkonce.armextab.*) }
  .ARM.exidx   : {
    PROVIDE_HIDDEN (__exidx_start = .);
    *(.ARM.exidx* .gnu.linkonce.armexidx.*)
    PROVIDE_HIDDEN (__exidx_end = .);
  }
  .eh_frame_hdr   : { *(.eh_frame_hdr) *(.eh_frame_entry .eh_frame_entry.*) }
  .eh_frame       : ONLY_IF_RO { KEEP (*(.eh_frame)) *(.eh_frame.*) }
  .sframe         : ONLY_IF_RO { KEEP (*(.sframe)) *(.sframe.*) }
  .gnu_extab   : ONLY_IF_RO { *(.gnu_extab*) }
  .gnu_except_table   : ONLY_IF_RO { *(.gnu_except_table .gnu_except_table.*) }
  .exception_ranges   : ONLY_IF_RO { *(.exception_ranges*) }
  /* Various note sections.  Placed here so that they are always included
     in the read-only segment and not treated as orphan sections.  The
     current orphan handling algorithm does place note sections after R/O
     data, but this is not guaranteed to always be the case.  */
  .note.build-id :      { *(.note.build-id) }
  .note.GNU-stack :     { *(.note.GNU-stack) }
  .note.gnu.property :  { *(.note.gnu.property) }
  .note.ABI-tag :       { *(.note.ABI-tag) }
  .note.package :       { *(.note.package) }
  .note.dlopen :        { *(.note.dlopen) }
  .note.netbsd.ident :  { *(.note.netbsd.ident) }
  .note.openbsd.ident : { *(.note.openbsd.ident) }
  /* End of the Read-only sections.  */
  /* Adjust the address for the data segment.  We want to adjust up to
     the same address within the page on the next page up.  */
  . = DATA_SEGMENT_ALIGN (CONSTANT (MAXPAGESIZE), CONSTANT (COMMONPAGESIZE));
  /* Thread Local Storage sections - MUST be 32-byte aligned for ARM32 bionic */
  .tdata		:
  {
    PROVIDE_HIDDEN (__tdata_start = .);
    *(.tdata .tdata.* .gnu.linkonce.td.*)
    . = ALIGN(32);
  }
  .tbss		: {
    *(.tbss .tbss.* .gnu.linkonce.tb.*) *(.tcommon)
    . = ALIGN(32);
  }
  .preinit_array     :
  {
    PROVIDE_HIDDEN (__preinit_array_start = .);
    *(.preinit_array)
    PROVIDE_HIDDEN (__preinit_array_end = .);
  }
  .init_array     :
  {
    PROVIDE_HIDDEN (__init_array_start = .);
    *(SORT_BY_INIT_PRIORITY(.init_array))
    PROVIDE_HIDDEN (__init_array_end = .);
  }
  .fini_array     :
  {
    PROVIDE_HIDDEN (__fini_array_start = .);
    *(SORT_BY_INIT_PRIORITY(.fini_array))
    PROVIDE_HIDDEN (__fini_array_end = .);
  }
  .data.rel.ro : { *(.data.rel.ro.local* .gnu.linkonce.d.rel.ro.*) *(.data.rel.ro .data.rel.ro.* .gnu.linkonce.d.rel.ro.*) }
  .data.rel.ro.local	: { *(.data.rel.ro.local* .gnu.linkonce.d.rel.ro.local.*) }
  .dynamic        : { *(.dynamic) }
  __got_start = .;
  .got            : { *(.got) }
  .got.plt        : { *(.got.plt) }
  .data           :
  {
    *(.data .data.* .gnu.linkonce.d.*)
    SORT(CONSTRUCTORS)
  }
  .data1          : { *(.data1) }
  _edata = .;
  PROVIDE (edata = .);
  __bss_start = .;
  .bss            :
  {
    *(.dynbss)
    *(.bss .bss.* .gnu.linkonce.b.*)
    *(COMMON)
    . = ALIGN(32);
  }
  . = ALIGN(16384);
  . = ALIGN(32);
  .bss            :
  {
    *(.bss .bss.* .gnu.linkonce.b.*)
    *(COMMON)
    . = ALIGN(32);
  }
  . = ALIGN(16384);
  _end = .;
  PROVIDE (end = .);
  __bss_end__ = .;
  /* Stabs debugging sections.  */
  .stab          : { *(.stab) }
  .stabstr       : { *(.stabstr) }
  .stab.excl     : { *(.stab.excl) }
  .stab.exclstr  : { *(.stab.exclstr) }
  .stab.index    : { *(.stab.index) }
  .stab.exclstr  : { *(.stab.exclstr) }
  .stab.indexstr  : { *(.stab.indexstr) }
  .comment       : { *(.comment) }
  /* DWARF debug sections.
     Symbols in the DWARF debugging sections are relative to the beginning
     of the section so we can use them in the final executable as is.
     They are not copied into the executable, but kept in the object files.
     The section names are from the DWARF standard.  */
  .debug          { *(.debug) }
  .debug_line     { *(.debug_line) }
  .debug_info     { *(.debug_info) }
  .debug_abbrev   { *(.debug_abbrev) }
  .debug_loc      { *(.debug_loc .debug_loc.*) }
  .debug_aranges  { *(.debug_aranges .debug_aranges.*) }
  .debug_macinfo  { *(.debug_macinfo .debug_macinfo.*) }
  .debug_ranges   { *(.debug_ranges .debug_ranges.*) }
  /* Stabs debugging sections.  */
  .stab          : { *(.stab) }
  .stabstr       : { *(.stabstr) }
  .stab.excl     : { *(.stab.excl) }
  .stab.exclstr  : { *(.stab.exclstr) }
  .stab.index    : { *(.stab.index) }
  .stab.exclstr  : { *(.stab.exclstr) }
  .stab.indexstr  : { *(.stab.indexstr) }
  /* DWARF debug sections with .zdebug prefix.  */
  .zdebug_info          : { *(.zdebug_info) }
  .zdebug_abbrev        : { *(.zdebug_abbrev) }
  .zdebug_line          : { *(.zdebug_line) }
  .zdebug_str           : { *(.zdebug_str) }
  .zdebug_loc           : { *(.zdebug_loc .zdebug_loc.*) }
  .zdebug_ranges        : { *(.zdebug_ranges .zdebug_ranges.*) }
  /* These sections are not to be loaded into memory, so we do not
     assign them a load address.  */
  .note.gnu.property   { *(.note.gnu.property) }
  .note.gnu.build-id    { *(.note.gnu.build-id) }
  .note.gnu.gold_version { *(.note.gnu.gold_version) }
  .note.ABI-tag        { *(.note.ABI-tag) }
  .note.gnu.property  { *(.note.gnu.property) }
  .note.android.ident  { *(.note.android.ident) }
  .note.ABI-tag         { *(.note.ABI-tag) }
  .note.package         { *(.note.package) }
  .note.note.gnu.build-id { *(.note.gnu.build-id) }
  .note.GNU-stack       { *(.note.GNU-stack) }
  .note.ABI-tag         { *(.note.ABI-tag) }
  .note.ABI-tag         { *(.note.ABI-tag) }
  .note.gnu.property    { *(.note.gnu.property) }
  .note.GNU-stack       { *(.note.GNU-stack) }
  .note.GNU-stack       { *(.note.GNU-stack) }
  .note.gnu.build-id    { *(.note.gnu.build-id) }
  .note.gnu.gold_version { *(.note.gnu.gold_version) }
  .note.gnu.property    { *(.note.gnu.property) }
  .note.gnu.property    { *(.note.gnu.property) }
  /* End of section descriptions. */
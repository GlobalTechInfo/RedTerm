/* RedTerm: proot TLS fix for i386 (i686 / "x86").
 *
 * bionic's static-TLS check has no underalignment gate on i386 (the ARM-only
 * abi_tpoff/actual_tpoff comparison in bionic/libc/bionic/bionic_elf_tls.cpp),
 * so a plain link works here; this script exists for uniformity with the
 * armeabi-v7a build and to START-align .tdata/.tbss at 32 so the emitted
 * PT_TLS stays consistent with the ARM build.
 *
 * Full layout script matching the plain NDK link (base 0x400000, 16 KB
 * page-aligned LOADs); lld-compatible (no GNU-only constants).
 */
SECTIONS
{
  . = 0x400000;
  .text : {
    KEEP(*(.text.startup))
    *(.text.unlikely .text.*_unlikely .text.unlikely.*)
    *(.text.exit .text.exit.*)
    *(.text.hot .text.hot.*)
    *(SORT(.text.sorted.*))
    *(.text .stub .text.* .gnu.linkonce.t.*)
    *(.gnu.warning)
  }
  .fini : { KEEP(*(SORT_NONE(.fini))) }
  PROVIDE (__etext = .);
  PROVIDE (_etext = .);
  PROVIDE (etext = .);
  .rodata : { *(.rodata .rodata.* .gnu.linkonce.r.*) }
  .rodata1 : { *(.rodata1) }
  .eh_frame_hdr : { *(.eh_frame_hdr) *(.eh_frame_entry .eh_frame_entry.*) }
  .eh_frame : ONLY_IF_RO { KEEP (*(.eh_frame)) *(.eh_frame.*) }
  .sframe : ONLY_IF_RO { KEEP (*(.sframe)) *(.sframe.*) }
  .note.GNU-stack : { *(.note.GNU-stack) }
  .note.gnu.property : { *(.note.gnu.property) }
  .note.ABI-tag : { *(.note.ABI-tag) }
  .note.android.ident : { *(.note.android.ident) }
  /* Align the data segment to the 16 KB page size (see -z max-page-size). */
  . = ALIGN(16384);
  .tdata : ALIGN(32) {
    PROVIDE_HIDDEN (__tdata_start = .);
    *(.tdata .tdata.* .gnu.linkonce.td.*)
  }
  .preinit_array : {
    PROVIDE_HIDDEN (__preinit_array_start = .);
    *(.preinit_array)
    PROVIDE_HIDDEN (__preinit_array_end = .);
  }
  .init_array : {
    PROVIDE_HIDDEN (__init_array_start = .);
    *(SORT_BY_INIT_PRIORITY(.init_array .init_array.*))
    PROVIDE_HIDDEN (__init_array_end = .);
  }
  .fini_array : {
    PROVIDE_HIDDEN (__fini_array_start = .);
    *(SORT_BY_INIT_PRIORITY(.fini_array .fini_array.*))
    PROVIDE_HIDDEN (__fini_array_end = .);
  }
  .data.rel.ro : {
    *(.data.rel.ro.local* .gnu.linkonce.d.rel.ro.*)
    *(.data.rel.ro .data.rel.ro.* .gnu.linkonce.d.rel.ro.*)
  }
  .data.rel.ro.local : { *(.data.rel.ro.local* .gnu.linkonce.d.rel.ro.local.*) }
  .dynamic : { *(.dynamic) }
  .got : { *(.got) *(.got.plt) }
  .data : {
    *(.data .data.* .gnu.linkonce.d.*)
    SORT(CONSTRUCTORS)
  }
  .data1 : { *(.data1) }
  _edata = .;
  PROVIDE (edata = .);
  __bss_start = .;
  .bss : {
    *(.dynbss)
    *(.bss .bss.* .gnu.linkonce.b.*)
    *(COMMON)
    . = ALIGN(32);
  }
  _end = .;
  PROVIDE (end = .);
  __bss_end__ = .;
  .comment : { *(.comment) }
}

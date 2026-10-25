/* RedTerm: proot TLS fix for 32-bit ARM (armeabi-v7a).
 *
 * bionic's static-TLS layout check (StaticTlsLayout::reserve_exe_segment_and_tcb,
 * bionic/libc/bionic/bionic_elf_tls.cpp) aborts an ARM32 executable when
 *   align_checked(2*word) != align_checked(tcb_size_post)
 * where the check folds in both p_align AND the skew p_vaddr % p_align.
 * A plain NDK link emits PT_TLS p_align=8 with a 16-aligned p_vaddr, which
 * aborts with "executable's TLS segment is underaligned ... needs to be at
 * least 32 for ARM Bionic".
 *
 * The fix is to make the linker START-align .tdata/.tbss at 32, so the
 * emitted PT_TLS has p_align=32 and p_vaddr % 32 == 0 (skew 0). Patching the
 * program header by hand is NOT enough: p_vaddr also feeds init_ptr, the
 * address bionic memcpies the TLS template from, so vaddr and the section's
 * real location must stay equal, which only a relink can guarantee.
 *
 * This is a full layout script (plain NDK linking produced base 0x10000,
 * 16 KB-aligned LOADs); it is lld-compatible (no GNU-only constants).
 */
SECTIONS
{
  . = 0x10000;
  .text : {
    KEEP(*(.text.startup))
    *(.text.unlikely .text.*_unlikely .text.unlikely.*)
    *(.text.exit .text.exit.*)
    *(.text.hot .text.hot.*)
    *(SORT(.text.sorted.*))
    *(.text .stub .text.* .gnu.linkonce.t.*)
    *(.gnu.warning)
    *(.glue_7t) *(.glue_7) *(.vfp11_veneer) *(.v4_bx)
  }
  .fini : { KEEP(*(SORT_NONE(.fini))) }
  PROVIDE (__etext = .);
  PROVIDE (_etext = .);
  PROVIDE (etext = .);
  .rodata : { *(.rodata .rodata.* .gnu.linkonce.r.*) }
  .rodata1 : { *(.rodata1) }
  .ARM.extab : { *(.ARM.extab* .gnu.linkonce.armextab.*) }
  .ARM.exidx : {
    PROVIDE_HIDDEN (__exidx_start = .);
    *(.ARM.exidx* .gnu.linkonce.armexidx.*)
    PROVIDE_HIDDEN (__exidx_end = .);
  }
  .eh_frame_hdr : { *(.eh_frame_hdr) *(.eh_frame_entry .eh_frame_entry.*) }
  .eh_frame : ONLY_IF_RO { KEEP (*(.eh_frame)) *(.eh_frame.*) }
  .sframe : ONLY_IF_RO { KEEP (*(.sframe)) *(.sframe.*) }
  .gnu_extab : ONLY_IF_RO { *(.gnu_extab*) }
  .gnu_except_table : ONLY_IF_RO { *(.gnu_except_table .gnu_except_table.*) }
  .exception_ranges : ONLY_IF_RO { *(.exception_ranges*) }
  .note.build-id : { *(.note.build-id) }
  .note.GNU-stack : { *(.note.GNU-stack) }
  .note.gnu.property : { *(.note.gnu.property) }
  .note.ABI-tag : { *(.note.ABI-tag) }
  .note.package : { *(.note.package) }
  .note.dlopen : { *(.note.dlopen) }
  .note.netbsd.ident : { *(.note.netbsd.ident) }
  .note.openbsd.ident : { *(.note.openbsd.ident) }
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
  .got : { *(.got.plt) *(.got) }
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
  .stab : { *(.stab) }
  .stabstr : { *(.stabstr) }
  .stab.excl : { *(.stab.excl) }
  .stab.exclstr : { *(.stab.exclstr) }
  .stab.index : { *(.stab.index) }
  .stab.indexstr : { *(.stab.indexstr) }
  .comment : { *(.comment) }
  .note.android.ident : { *(.note.android.ident) }
}

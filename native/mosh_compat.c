/*
 * Android stand-ins for glibc functions mosh calls that bionic does not have at this
 * app's minimum API.
 */
#include <langinfo.h>

/*
 * nl_langinfo is declared in bionic only from API 26:
 *
 *     #if __ANDROID_API__ >= 26
 *     char* _Nonnull nl_langinfo(nl_item __item) __INTRODUCED_IN(26);
 *     #endif
 *
 * and the symbol genuinely does not exist on an API 24 device, so this is not only a
 * declaration problem -- RedTerm supports API 24.
 *
 * mosh asks for exactly one item. src/util/locale_utils.cc's locale_charset() calls
 * nl_langinfo(CODESET) for the sole purpose of rewriting glibc's "ANSI_X3.4-1968"
 * into something readable, and nothing else in the tree uses it. So answering
 * CODESET is the entire requirement, and answering it with UTF-8 is accurate here:
 * ProotLaunch exports LANG/LC_ALL=C.UTF-8 when the rootfs offers it, so a session is
 * not in the C locale by the time this runs.
 */
char *nl_langinfo(int item)
{
	if (item == CODESET) {
		return (char *)"UTF-8";
	}
	return (char *)"";
}

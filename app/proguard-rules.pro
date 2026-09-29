# Deliberately close to empty.
#
# Nothing in this app uses reflection, a serialization library, or JNI,
# so there's nothing that needs an explicit -keep rule beyond what
# Compose's own AAR-bundled consumer rules already provide. If a crash
# report ever points at an obfuscated stack trace that's hard to read,
# the mapping file for that build (app/build/outputs/mapping/release/mapping.txt)
# de-obfuscates it — see the "Retrace" section of the Android docs.

# Keep line numbers in stack traces for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

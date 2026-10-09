#pragma once
#include <CoreFoundation/CoreFoundation.h>
#include <stdint.h>

void *BMCreateSource(uint32_t *result);
uint32_t BMUpdateSource(void *source, CFDictionaryRef details);
void BMReleaseSource(void *source);
int BMCountPublishedSources(void);
int BMCountEligibleSources(void);
int BMCountSourcesWithGlyph(void);
CFArrayRef BMCopyComputerSources(void) CF_RETURNS_RETAINED;
bool BMHasAndroidUSBDevice(void);

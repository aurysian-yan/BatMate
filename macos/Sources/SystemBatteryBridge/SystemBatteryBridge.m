#import "SystemBatteryBridge.h"
#import <Foundation/Foundation.h>
#import <IOKit/ps/IOPowerSources.h>
#import <objc/message.h>
#import <dlfcn.h>

// 动态适配系统配件电源接口；退出时撤销本进程登记的设备。
static void *library(void) {
    static void *handle;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ handle = dlopen("/System/Library/Frameworks/IOKit.framework/IOKit", RTLD_NOW); });
    return handle;
}

void *BMCreateSource(uint32_t *result) {
    uint32_t (*create)(void **) = dlsym(library(), "IOPSCreatePowerSource");
    void *source = NULL;
    *result = create ? create(&source) : 0xe00002c7;
    return source;
}

uint32_t BMUpdateSource(void *source, CFDictionaryRef details) {
    uint32_t (*update)(void *, CFDictionaryRef) = dlsym(library(), "IOPSSetPowerSourceDetails");
    return update ? update(source, details) : 0xe00002c7;
}

void BMReleaseSource(void *source) {
    uint32_t (*release)(void *) = dlsym(library(), "IOPSReleasePowerSource");
    if (source && release) release(source);
}

static int countSources(BOOL eligibleOnly, BOOL requireGlyph) {
    CFTypeRef (*copy)(int) = dlsym(library(), "IOPSCopyPowerSourcesByType");
    if (!copy) return -1;
    CFTypeRef info = copy(0);
    if (!info) return -1;
    CFArrayRef list = IOPSCopyPowerSourcesList(info);
    if (!list) { CFRelease(info); return -1; }
    NSArray *items = (__bridge NSArray *)list;
    SEL glyphSelector = NSSelectorFromString(@"batteryWidgetGlyphName:");
    if (requireGlyph && !dlopen("/System/Library/PrivateFrameworks/BatteryCenterUI.framework/BatteryCenterUI", RTLD_NOW)) {
        CFRelease(list); CFRelease(info); return -1;
    }
    if (eligibleOnly) {
        if (!dlopen("/System/Library/PrivateFrameworks/BatteryCenter.framework/BatteryCenter", RTLD_NOW)) {
            CFRelease(list); CFRelease(info); return -1;
        }
        Class controller = NSClassFromString(@"BCBatteryDeviceController");
        SEL shared = NSSelectorFromString(@"_sharedPowerSourceController");
        if (![controller respondsToSelector:shared]) { CFRelease(list); CFRelease(info); return -1; }
        id provider = ((id (*)(id, SEL))objc_msgSend)(controller, shared);
        SEL ordered = NSSelectorFromString(@"_orderedDevicesFromPowerSourcesBlob:powerSourcesList:");
        if (![provider respondsToSelector:ordered]) { CFRelease(list); CFRelease(info); return -1; }
        items = ((id (*)(id, SEL, CFTypeRef, CFArrayRef))objc_msgSend)(provider, ordered, info, list);
    }
    int matches = 0;
    for (id item in items) {
        NSString *identifier = eligibleOnly ? [item valueForKey:@"accessoryIdentifier"] :
            ((__bridge NSDictionary *)IOPSGetPowerSourceDescription(info, (__bridge CFTypeRef)item))[@"Accessory Identifier"];
        if (![identifier hasPrefix:@"app.batmate."]) continue;
        if (requireGlyph) {
            if (![item respondsToSelector:glyphSelector]) { matches = -1; break; }
            BOOL flag = NO;
            NSString *glyph = ((id (*)(id, SEL, BOOL *))objc_msgSend)(item, glyphSelector, &flag);
            NSString *expected = [identifier isEqualToString:@"app.batmate.phone"] ?
                @"iphone.smartbatterycase.gen2" : @"applewatch";
            if (![glyph isEqualToString:expected]) continue;
        }
        matches++;
    }
    CFRelease(list); CFRelease(info);
    return matches;
}

int BMCountPublishedSources(void) { return countSources(NO, NO); }
int BMCountEligibleSources(void) { return countSources(YES, NO); }
int BMCountSourcesWithGlyph(void) { return countSources(YES, YES); }

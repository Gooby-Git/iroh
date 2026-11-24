# usage: python3 -I hw-feature-sanitize.py profile.json   (in place)
# Makes the profile's FEATURES a subset of what lavapipe supports, using the Profiles layer's own warnings
# ("'x' profile value is enabled in the profile, but the device ... does not support it"). The warning names a member, not its struct, so:
# pass 1 zeroes the member in every struct EXCEPT the core aggregates (VkPhysicalDeviceVulkan11/12/13/14Features), pass 2 re-runs the layer and
# zeroes what is still warned inside the aggregates (a name like bufferDeviceAddress is unsupported in the old EXT struct but supported in Vulkan12Features).
import json,os,re,subprocess,sys
prof=sys.argv[1]
env=dict(os.environ,VK_DRIVER_FILES='/usr/share/vulkan/icd.d/lvp_icd.json',VK_LOADER_LAYERS_DISABLE='~implicit~',VK_LOADER_LAYERS_ENABLE='',
 VK_INSTANCE_LAYERS='VK_LAYER_KHRONOS_profiles',VK_KHRONOS_PROFILES_PROFILE_FILE=prof,
 VK_KHRONOS_PROFILES_SIMULATE_CAPABILITIES='SIMULATE_API_VERSION_BIT,SIMULATE_FEATURES_BIT,SIMULATE_PROPERTIES_BIT,SIMULATE_EXTENSIONS_BIT',
 VK_KHRONOS_PROFILES_DEBUG_REPORTS='DEBUG_REPORT_WARNING_BIT')
AGG=re.compile(r'^VkPhysicalDeviceVulkan1\dFeatures$')
def warned():
    d=json.load(open(prof)); env['VK_KHRONOS_PROFILES_PROFILE_NAME']=list(d['profiles'])[0]
    r=subprocess.run(['vulkaninfo','--summary'],capture_output=True,text=True,env=env); out=r.stdout+r.stderr
    return set(re.findall(r"WARNING: '([A-Za-z0-9_]+)' profile value is enabled in the profile, but the device",out))
def zero(names,aggregates):
    d=json.load(open(prof)); n=0
    for sname,s in d['capabilities']['device'].get('features',{}).items():
        if not isinstance(s,dict) or bool(AGG.match(sname))!=aggregates: continue
        for k in list(s):
            if k in names and s[k] is True: s[k]=False; n+=1
    json.dump(d,open(prof,'w'),indent=1); return n
w1=warned(); n1=zero(w1,False)
w2=warned(); n2=zero(w2,True)
print(os.path.basename(prof),'pass1 names',len(w1),'zeroed',n1,'| pass2 names',len(w2),'zeroed',n2)

# usage: python3 -I hw-fmt-intersect.py profile.json probe-binary (built from hw-fmt-probe.c with gcc -lvulkan); rewrites formats := profile ∩ lavapipe
import json,sys,re,subprocess,xml.etree.ElementTree as ET
prof=sys.argv[1]; probe=sys.argv[2]
vk=ET.parse('/usr/share/vulkan/registry/vk.xml').getroot()
fmt={};bits={}
for e in vk.iter('enums'):
    n=e.get('name')
    if n=='VkFormat':
        for x in e.iter('enum'):
            v=x.get('value')
            if v is not None and x.get('alias') is None: fmt[x.get('name')]=int(v)
    if n in ('VkFormatFeatureFlagBits','VkFormatFeatureFlagBits2'):
        for x in e.iter('enum'):
            if x.get('alias'): continue
            b=x.get('bitpos'); v=x.get('value')
            val=(1<<int(b)) if b is not None else (int(v,0) if v else None)
            if val is not None: bits[x.get('name')]=val
# extension-added VkFormat values
for ex in vk.iter('extension'):
    num=int(ex.get('number'))
    for en in ex.iter('enum'):
        if en.get('extends')=='VkFormat' and en.get('alias') is None:
            off=en.get('offset')
            if off is not None:
                v=1000000000+(num-1)*1000+int(off); 
                if en.get('dir')=='-': v=-v
                fmt[en.get('name')]=v
        if en.get('extends') in('VkFormatFeatureFlagBits2','VkFormatFeatureFlagBits') and en.get('bitpos') and en.get('alias') is None:
            bits[en.get('name')]=1<<int(en.get('bitpos'))
d=json.load(open(prof)); F=d['capabilities']['device'].get('formats',{})
names=[n for n in F if n in fmt]
out=subprocess.run([probe],input='\n'.join(str(fmt[n]) for n in names)+'\n',capture_output=True,text=True,env=dict(__import__('os').environ,VK_DRIVER_FILES='/usr/share/vulkan/icd.d/lvp_icd.json',VK_LOADER_LAYERS_DISABLE='~implicit~',VK_INSTANCE_LAYERS='',VK_LOADER_LAYERS_ENABLE='')).stdout.split('\n')
dev={}
for l in out:
    p=l.split()
    if len(p)==4: dev[int(p[0])]=tuple(int(x) for x in p[1:])
dropped=0
for n in names:
    if fmt[n] not in dev: continue
    lin,opt,buf=dev[fmt[n]]
    for key,mask in (('VkFormatProperties',None),('VkFormatProperties3',None),('VkFormatProperties3KHR',None)):
        s=F[n].get(key)
        if not s: continue
        for k,m in (('linearTilingFeatures',lin),('optimalTilingFeatures',opt),('bufferFeatures',buf)):
            if k in s:
                keep=[b for b in s[k] if (bits.get(b) is None) or (bits[b]&m)==bits[b] or (b.startswith('VK_FORMAT_FEATURE_') and not b.startswith('VK_FORMAT_FEATURE_2_') and (bits[b]&m)==bits[b])]
                dropped+=len(s[k])-len(keep); s[k]=keep
json.dump(d,open(prof,'w'),indent=1)
print(prof.split('/')[-1],'formats',len(names),'bits dropped',dropped)

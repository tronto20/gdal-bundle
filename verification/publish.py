#!/usr/bin/env python3
"""Publish the complete, already tested repository without rebuilding on another host."""
import argparse
import json
import os
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from central_release import CentralRelease
from publication_versions import expected_coordinates, validate_versions

ROOT=Path(__file__).resolve().parent.parent
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--repo',type=Path,default=ROOT/'verification/build/consumer-maven')
p.add_argument('--destination',type=Path,help='Local verification repository; never contacts a remote server')
a=p.parse_args()
base=a.repo.resolve()/'dev/tronto/gdal4k'
expected=expected_coordinates()
if {d.name for d in base.iterdir() if d.is_dir()}!=expected:raise SystemExit('Incomplete or unexpected Maven coordinate set')
items=[]
for directory in sorted(base.iterdir()):
    poms=list(directory.glob('*/*.pom'))
    if len(poms)!=1:raise SystemExit('Expected one tested version per coordinate: '+str(directory))
    pom=poms[0]; xml=ET.parse(pom).getroot(); ns={'m':'http://maven.apache.org/POM/4.0.0'}
    coordinate=[xml.findtext('m:'+k,namespaces=ns) for k in ['groupId','artifactId','version']]
    prefix=coordinate[1]+'-'+coordinate[2]
    artifacts=[]
    for f in sorted(pom.parent.iterdir()):
        if f.suffix not in ['.jar','.klib','.aar','.module']:continue
        if not f.stem.startswith(prefix):raise SystemExit('Unexpected artifact '+str(f))
        classifier=f.stem[len(prefix):].removeprefix('-')
        artifacts.append({'file':str(f),'extension':f.suffix[1:],'classifier':classifier})
    if not artifacts:raise SystemExit('Missing tested payload '+str(pom))
    items.append({'coordinate':coordinate,'pom':str(pom),'artifacts':artifacts})

version=validate_versions(items)
target=os.environ.get('PUBLISH_TARGET') or 'mavenCentral'
if target not in {'mavenCentral','githubPackages'}:raise SystemExit('Unexpected PUBLISH_TARGET')
release=None
if not a.destination and target=='mavenCentral' and not version.endswith('SNAPSHOT'):
    release=CentralRelease.from_environment()
    if not ('dev.tronto.gdal4k'==release.namespace or 'dev.tronto.gdal4k'.startswith(release.namespace+'.')):
        raise SystemExit('MAVEN_CENTRAL_NAMESPACE must own dev.tronto.gdal4k')

with tempfile.TemporaryDirectory(prefix='gdal4k-verified-publisher-') as temporary:
    project=Path(temporary)
    (project/'settings.gradle').write_text('rootProject.name="gdal4k-verified-publisher"\n')
    (project/'artifacts.json').write_text(json.dumps(items))
    destination=a.destination.resolve().as_uri() if a.destination else None
    build='''import groovy.json.JsonSlurper
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
plugins { id 'maven-publish'; id 'signing' }
def localDestination = '''+json.dumps(destination)+'''
def central = !localDestination && (System.getenv('PUBLISH_TARGET') ?: 'mavenCentral') == 'mavenCentral'
def items = new JsonSlurper().parse(file('artifacts.json'))
def snapshot = items.every { it.coordinate[2].endsWith('SNAPSHOT') }
publishing {
    publications {
        items.eachWithIndex { item, index ->
            create('verified'+index, MavenPublication) {
                groupId=item.coordinate[0]; artifactId=item.coordinate[1]; version=item.coordinate[2]
                item.artifacts.each { payload ->
                    artifact(payload.file) { extension=payload.extension; if(payload.classifier) classifier=payload.classifier }
                }
            }
        }
    }
    repositories {
        maven {
            name='Verified'
            url=uri(localDestination ?: (central ? (snapshot ? 'https://central.sonatype.com/repository/maven-snapshots/' : 'https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/') : System.getenv('GITHUB_PACKAGES_URL')))
            if(!localDestination) credentials {
                username=System.getenv(central ? 'MAVEN_CENTRAL_USERNAME' : 'GITHUB_ACTOR')
                password=System.getenv(central ? 'MAVEN_CENTRAL_PASSWORD' : 'GITHUB_TOKEN')
            }
        }
    }
}
items.eachWithIndex { item, index ->
    tasks.named('generatePomFileForVerified'+index+'Publication', GenerateMavenPom) {
        doLast { destination.text=file(item.pom).getText('UTF-8') }
    }
}
if(central && !snapshot) signing {
    def key=System.getenv('SIGNING_KEY')
    if(key && !key.contains('-----BEGIN PGP')) key=new String(Base64.mimeDecoder.decode(key),'UTF-8')
    useInMemoryPgpKeys(System.getenv('SIGNING_KEY_ID')?.trim() ?: null,key,System.getenv('SIGNING_PASSWORD') ?: '')
    sign publishing.publications
}
'''
    (project/'build.gradle').write_text(build)
    subprocess.run([str(ROOT/('gradlew.bat' if os.name=='nt' else 'gradlew')),'-p',str(project),'--console=plain','publish'],check=True)

if release:
    deployment=release.finalize()
    print('Maven Central publication complete: '+deployment,flush=True)

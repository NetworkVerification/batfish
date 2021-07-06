import os
import sys
import string
import subprocess
import numpy
import tmgen
import random
import time
import argparse
import io
# def generateInitFunction(sourcePref)
FNULL = open(os.devnull, 'w')
path = "exampleNetworks/"

def makeName(networkName, numberOfLinkFailures):
    return networkName + "_" + str(numberOfLinkFailures)

# Returns the prefixes originated, mappings from nodes to prefixes and from prefixes to nodes.
def getNetworkInfo(network, failures):
    extra = "" if failures is None else failures
    prefixesFile = open(network+extra+"/"+network+extra+"Prefixes.csv")
    # Set of prefixes announced in the network
    prefixes = []
    # Mapping from nodes to prefixes they announce.
    nodeToPrefixes = {}
    # Mapping from prefixes to nodes
    prefixToNode = {}

    for line in prefixesFile.readlines() :
        fields = line.split(",")
        if len(fields) == 2 :
            node = fields[0]
            dst = fields[1].rstrip('\n\r')
            nodePrefixes = nodeToPrefixes.get(node)
            if nodePrefixes == None:
                nodePrefixes = set()
            nodePrefixes.add(dst)
            prefixes.append(dst)
            nodeToPrefixes.update({node:nodePrefixes})
            prefixToNode.update({dst:node})


    topologyFile = open(network+extra+"/"+network+extra+"Topology.csv")
    edgeToIds = {} # real edge names to ids
    idsToEdges = {} # ids to real edge names
    edgeToNvEdge = {} # real edge to nv edge
    nvEdgeToEdge = {} # nv edges to real edges
    for line in topologyFile.readlines() :
        fields = line.rstrip().split(",")
        if len(fields) == 3:
            edgeName = fields[0]
            nvName = fields[1]
            edgeId = fields[2]
            edgeToIds.update({edgeName:edgeId})
            idsToEdges.update({edgeId:edgeName})
            nvEdgeToEdge.update({nvName:edgeName})
            edgeToNvEdge.update({edgeName:nvName})

    failureModelFile = open(network+extra+"/"+network+extra+"Failure.csv")
    linkCapacities = {}
    for line in failureModelFile.readlines() :
        fields = line.rstrip().split(",")
        if len(fields) == 4:
            nvName = fields[1]
            edgeCap = float(fields[3])
            linkCapacities.update({nvName:edgeCap})

    return {'networkName':network, 'prefixes':prefixes, 'nodeToPrefixes':nodeToPrefixes, 'prefixToNode':prefixToNode, 
    'edgeToIds':edgeToIds, 'idsToEdges':idsToEdges, 'edgeToNvEdge':edgeToNvEdge ,'nvEdgeToEdge':nvEdgeToEdge, 'linkCapacities':linkCapacities}

# Generate a traffic matrix based on the given networkInfo and traffic model.
def generateTraffic(networkInfo, model, traffic):
    print("Generating traffic")
    network = networkInfo.get('networkName')
    prefixes = networkInfo.get('prefixes')
    nodeToPrefixes = networkInfo.get('nodeToPrefixes')
    prefixToNode = networkInfo.get('prefixToNode')

    # Either generate a random traffic matrix where the mean traffic in the network is [traffic]
    if model == "random":
        trafficmatrix = tmgen.models.random_gravity_tm(num_nodes=len(prefixes), mean_traffic=traffic).worst_case().at_time(0)
    else:
        # or a traffix matrix where the total traffic is [traffic] and it is equally distributed among each traffic class.
        popSize = len(prefixes)
        pop = [1/popSize] * popSize
        trafficmatrix = tmgen.models.gravity_tm(populations=pop, total_traffic=traffic).worst_case().at_time(0)

    # trafficTo: dstPre -> (srcPrefix, srcNode, trafficLoad)
    trafficTo = {}
    for dstPre in prefixes:
        for node in nodeToPrefixes.keys():
        # Only capture traffic among different nodes
            if (prefixToNode.get(dstPre) != node):
                # Choose a random prefix from the source node (the min actually).
                srcPre = min(nodeToPrefixes.get(node))
                incomingTraffic = trafficTo.get(dstPre)
                if incomingTraffic == None:
                    incomingTraffic = set()
                incomingTraffic.add((srcPre, prefixToNode.get(srcPre), trafficmatrix[prefixes.index(srcPre)][prefixes.index(dstPre)]))
                trafficTo.update({dstPre:incomingTraffic})

    trafficFile = open(network+"/"+network+"Traffic.csv", 'w')

    for perDst in trafficTo.items():
        dst = perDst[0]
        sourceSet = perDst[1]
        for source in sourceSet:
            trafficFile.write(dst + "," + source[0] + "," + source[1] + "," + str(source[2]) + "\n")
    trafficFile.close()

# Code to scale traffic in the network by factor.
def scaleTraffic(networkInfo, factor, outputFile) : 
    network = networkInfo.get('networkName')
    trafficFile = open(makeName(network,0)+"/"+makeName(network,0)+"Traffic.csv")
    trafficClasses = []
    for line in trafficFile.readlines(): 
        fields = line.rstrip().split(",")
        if len(fields) == 4 : 
            trafficClasses.append([fields[0], fields[1], fields[2], float(fields[3])*factor])

    trafficFile = open(outputFile, 'w')
    for tc in trafficClasses : 
        trafficFile.write(tc[0] + "," + tc[1] + "," + tc[2] + "," + str(tc[3]))
        trafficFile.write("\n")
    trafficFile.close()

# Find the Maximum-link utilization from verification of 0-link failure case.
def extractMLU(networkInfo, numberOfLinkFailures):
    network = networkInfo.get('networkName')
    linkCapacities = networkInfo.get('linkCapacities')
    edgeToNvEdge = networkInfo.get('edgeToNvEdge')
    idsToEdge = networkInfo.get('idsToEdges')
    luFile = open(makeName(network, numberOfLinkFailures)+"/"+"values.csv", 'r')
    lines = luFile.readlines()
    luFile.close()
    mlu = 0
    for line in lines :
        fields = line.strip().split(",")
        if len(fields) == 2 :
            edgeId = fields[0].split("_")[1]
            nvEdge = edgeToNvEdge.get(idsToEdge.get(edgeId))
            cap = linkCapacities.get(nvEdge)
            lu = float(fields[1]) / cap
            if lu > mlu:
                mlu = lu
    return mlu

 
def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("-v","--verify", action="store_true",
                    help="verifies the given network, takes as input the number of failures")
    parser.add_argument("-l","--linkFailures", type=int,
                    help="Number of link failures", default=0)
    parser.add_argument("--generateTM", help="generate a traffic matrix?", action="store_true")
    parser.add_argument("--tmMode", type=str, help="takes as input the type of matrix used (random or uniform)", default="uniform")
    parser.add_argument("network", type=str, help="takes as input the name of a network (must be present in the current working directory)", default="")
    parser.add_argument("--multipath", type=bool, help="Enable ECMP verification", default=True)
    parser.add_argument("--aspath", type=bool, help="model BGP ASPath (as a set)", default=False)
    args = parser.parse_args()
    if args.generateTM:
        networkInfo = getNetworkInfo(args.network, None)
        generateTraffic(networkInfo, args.tmMode, 1000)
    elif args.verify:
        # call batfish for 0 failures and have it generate a traffic matrix
        generateModel(args.network, 0, args.multipath, args.aspath, args.tmMode)
        networkInfo = getNetworkInfo(args.network, "_0")
        # run probNV, extract link utilizations for 0 failures.
        verifyModel(networkInfo, 0)
        # Compute MLU
        mlu = extractMLU(networkInfo, 0)
        # scale traffic matrix.
        random.seed(time.time())
        factor = float(random.randint(65, 75))/(100*mlu)
        tm = makeName(args.network,args.linkFailures)+"/"+makeName(args.network, args.linkFailures)+"Traffic.csv"
        if not os.path.exists(makeName(args.network,args.linkFailures)):
            os.makedirs(makeName(args.network,args.linkFailures))
        scaleTraffic(networkInfo, factor, tm)
        # call batfish for K failures and give the scaled TM as input.
        generateModel(args.network, int(args.linkFailures), args.multipath,  args.aspath, tm )
        # run probnv.
        verifyModel(networkInfo, args.linkFailures)


def generateModel(network, numberOfLinkFailures, multipath, aspath, trafficMatrix):
    commandFile = open("command", 'w')
    commandFile.write("init-snapshot " + (path + network) + " tr-compile-" + (network + "_" + str(numberOfLinkFailures)) + "\n")
    commandFile.write("get compile singlePrefix=\"true\", doNextHop=\"false\", doData=\"true\", doMultiPath=\"" + ("true" if multipath else "false") + "\", doNodeFaults=\"false\", doASPath=\"" + ("true" if aspath else "false") + "\", doBoundedLinkFaults=\"" + str(numberOfLinkFailures) + "\", symbolicOrder=\"\", doOrigin=\"false\", trafficMatrix=\"" + trafficMatrix + "\", file=\"" + makeName(network, numberOfLinkFailures)  + "\"")
    commandFile.close()
    #execute shell command
    d = os.environ.copy()
    subprocess.call(["allinone -cmd command"], env = d, shell=True, stdout=sys.stdout)
# cwd=HOME+"/arc-quantitative/projects/arc/target"

def verifyModel(networkInfo, numberOfLinkFailures):
    outputFile = open("verificationResult", 'w')
    network = networkInfo.get('networkName')
    d = os.environ.copy()
    subprocess.call(["probNV", "-new-sim", "-sim-skip", "0", "-verbose", "1", "-c", makeName(network, numberOfLinkFailures) + "/" + makeName(network, numberOfLinkFailures) + "_data.nv"], stdout=outputFile, env=d)


if __name__ == "__main__":
   main(sys.argv)





   # Call from this program, Batfish to generate a TM for 0 link failures.
   # Run verif for 0 failures.
   # Scale TM
   # provide scaled TM to batfish to generate new translation and Run verif for failures.
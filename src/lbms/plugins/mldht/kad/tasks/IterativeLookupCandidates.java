package lbms.plugins.mldht.kad.tasks;

import static java.lang.Math.max;

import lbms.plugins.mldht.kad.KBucketEntry;
import lbms.plugins.mldht.kad.Key;
import lbms.plugins.mldht.kad.RPCCall;
import lbms.plugins.mldht.kad.RPCState;

import java.net.InetAddress;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/*
 * Issues:
 * 
 * - spurious packet loss
 * - remotes might fake IDs. possibly with collusion.
 * - invalid results
 *   - duplicate IPs
 *   - duplicate IDs
 *   - wrong IDs -> might trip up fake ID detection!
 *   - IPs not belonging to DHT nodes -> DoS abuse
 * 
 * Solution:
 * 
 * - generally avoid querying an IP more than once
 * - dedup result lists from each node
 * - ignore responses with unexpected IDs. normally this could be abused to silence others, but...
 * - allow duplicate requests if many *separate* sources suggest precisely the same <id, ip, port> tuple
 * 
 * -> we can recover from all the above-listed issues because the terminal set of nodes should have some partial agreement about their neighbors
 * 
 * 
 * 
 */
public class IterativeLookupCandidates {
	
	Key target;
	Map<KBucketEntry, LookupGraphNode> candidates = new ConcurrentHashMap<>();
	// maybe split out call tracking
	Map<RPCCall, KBucketEntry> calls;
	Collection<Object> accepted;
	boolean allowRetransmits = true;
	
	class LookupGraphNode {
		final KBucketEntry e;
		Set<LookupGraphNode> sources = new CopyOnWriteArraySet<>();
		List<LookupGraphNode> returnedNodes = new CopyOnWriteArrayList<>() ;
		List<RPCCall> calls = new CopyOnWriteArrayList<>();
		
		public LookupGraphNode(KBucketEntry kbe) {
			e = kbe;
		}
		
		void addCall(RPCCall c) {
			calls.add(c);
		}
		
		void addSource(LookupGraphNode toAdd) {
			sources.add(toAdd);
		}
		
		boolean succeeded() {
			return calls.stream().anyMatch(c -> c.state() == RPCState.RESPONDED);
		}
		
		int nonSuccessfulDescendantCalls() {
			return (int) returnedNodes.stream().mapToDouble(node -> (node.succeeded() ? 0. : 1.) / Math.max(node.sources.size(), 1)).sum();
		}
		
		KBucketEntry toKbe() {
			return e;
		}
		
	}
	
	class Source {
		KBucketEntry e;
		List<RPCCall> sourcedCalls = new CopyOnWriteArrayList<>();
		
		void addCall(RPCCall c) {
			sourcedCalls.add(c);
		}
	}
	
	public IterativeLookupCandidates(Key target) {
		this.target = target;
		calls = new ConcurrentHashMap<>();
		candidates = new ConcurrentHashMap<>();
		accepted = new HashSet<>();
						
	}
	
	void allowRetransmits(boolean toggle) {
		allowRetransmits = toggle;
	}
	
	void addCall(RPCCall c, KBucketEntry kbe) {
		synchronized (this) {
			calls.put(c, kbe);
		}
		
		candidates.get(kbe).addCall(c);
	}
	
	KBucketEntry acceptResponse(RPCCall c) {
		// we ignore on mismatch, node will get a 2nd chance if sourced from multiple nodes and hasn't sent a successful reply yet
		synchronized (this) {
			if(!c.matchesExpectedID())
				return null;
			
			KBucketEntry kbe = calls.get(c);
			
			boolean insertOk = !accepted.contains(kbe.getAddress().getAddress()) && !accepted.contains(kbe.getID());
			if(insertOk) {
				accepted.add(kbe.getAddress().getAddress());
				accepted.add(kbe.getID());
				return kbe;
			}
			return null;
		}
	}
	
	void addCandidates(KBucketEntry source, Collection<KBucketEntry> entries) {
		Set<Object> dedup = new HashSet<>();
		
		LookupGraphNode sourceNode = null;
		if(source != null)
			sourceNode = candidates.get(source);
		
		for(KBucketEntry e : entries) {
			if(!dedup.add(e.getID()) || !dedup.add(e.getAddress().getAddress()))
				continue;
			
			synchronized (this) {
				LookupGraphNode node = candidates.computeIfAbsent(e, kbe -> {
					return new LookupGraphNode(kbe);
				});
				
				if(sourceNode != null)
					node.addSource(sourceNode);
			}
			
		}
		
		
	}
	
	Set<KBucketEntry> getSources(KBucketEntry e) {
		return candidates.get(e).sources.stream().map(LookupGraphNode::toKbe).collect(Collectors.toSet());
	}
	
	Comparator<Map.Entry<KBucketEntry, LookupGraphNode>> comp() {
		Comparator<KBucketEntry> d = new KBucketEntry.DistanceOrder(target);
		Comparator<LookupGraphNode> s = (a, b) -> b.returnedNodes.size() - a.returnedNodes.size();
		return Map.Entry.<KBucketEntry, LookupGraphNode>comparingByKey(d).thenComparing(Map.Entry.comparingByValue(s));
	}
	
	Optional<KBucketEntry> next() {
		synchronized (this) {
			return cand().min(comp()).map(Map.Entry::getKey);
		}
	}
	
	Stream<Map.Entry<KBucketEntry, LookupGraphNode>> cand() {
		return candidates.entrySet().stream().filter(me -> {
			KBucketEntry kbe = me.getKey();
			LookupGraphNode node = me.getValue();
			
			
			InetAddress addr = kbe.getAddress().getAddress();
			
			if(accepted.contains(addr) || accepted.contains(kbe.getID()))
				return false;

			// only do requests to nodes which have at least one source where the source has not given us lots of bogus candidates
			if(node.sources.size() > 0 && node.sources.stream().noneMatch(source -> source.nonSuccessfulDescendantCalls() < 3))
				return false;
			
			int dups = 0;
			for(RPCCall c : calls.keySet()) {
				if(!addr.equals(c.getRequest().getDestination().getAddress()))
					continue;
				
				if(!allowRetransmits)
					return false;
				
				// in flight, not stalled
				if(c.state() == RPCState.SENT || c.state() == RPCState.UNSENT)
					return false;
				
				// already got a response from that addr that does not match what we would expect from this candidate anyway
				if(c.state() == RPCState.RESPONDED && !c.getResponse().getID().equals(me.getKey().getID()))
					return false;
				// we don't strictly check the presence of IDs in error messages, so we can't compare those here
				if(c.state() == RPCState.ERROR)
					return false;
				dups++;
			}
			// log2 scale
			int sources = 31 - Integer.numberOfLeadingZeros(max(me.getValue().sources.size(), 1));
			//System.out.println("sd:" + sources + " " + dups);
			
			return sources >= dups;
		});
	}
	
	Stream<Map.Entry<KBucketEntry, LookupGraphNode>> allCand() {
		return candidates.entrySet().stream();
	}
	
	int numCalls(KBucketEntry kbe) {
		return (int) calls.entrySet().stream().filter(me -> me.getValue().equals(kbe)).count();
	}
	
	int numRsps(KBucketEntry kbe) {
		return (int) calls.keySet().stream().filter(c -> c.state() == RPCState.RESPONDED && c.getResponse().getID().equals(kbe.getID()) && c.getResponse().getOrigin().equals(kbe.getAddress())).count();
	}

}

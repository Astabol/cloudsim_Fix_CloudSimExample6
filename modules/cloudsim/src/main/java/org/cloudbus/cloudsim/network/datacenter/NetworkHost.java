/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.network.datacenter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmScheduler;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.lists.VmList;
import org.cloudbus.cloudsim.provisioners.BwProvisioner;
import org.cloudbus.cloudsim.provisioners.RamProvisioner;

/**
 * NetworkHost class extends {@link Host} to support simulation of networked datacenters. It executes
 * actions related to management of packets (sent and received) other than that of virtual machines
 * (e.g., creation and destruction). A host has a defined policy for provisioning memory and bw, as
 * well as an allocation policy for PE's to virtual machines.
 * 
 * <br/>Please refer to following publication for more details:<br/>
 * <ul>
 * <li><a href="http://dx.doi.org/10.1109/UCC.2011.24">Saurabh Kumar Garg and Rajkumar Buyya, NetworkCloudSim: Modelling Parallel Applications in Cloud
 * Simulations, Proceedings of the 4th IEEE/ACM International Conference on Utility and Cloud
 * Computing (UCC 2011, IEEE CS Press, USA), Melbourne, Australia, December 5-7, 2011.</a>
 * </ul>
 * 
 * @author Saurabh Kumar Garg
 * @author Remo Andreoli
 * @since CloudSim Toolkit 3.0
 */
public class NetworkHost extends Host {
        /**
         * List of received packets.
         */
	public List<NetworkPacket> pktReceived;

        /** 
         * Edge switch in which the Host is connected. 
         */
	public Switch sw;

	public NetworkHost(
			int id,
			RamProvisioner ramProvisioner,
			BwProvisioner bwProvisioner,
			long storage,
			List<? extends Pe> peList,
			VmScheduler vmScheduler) {
		super(id, ramProvisioner, bwProvisioner, storage, peList, vmScheduler);

		pktReceived = new ArrayList<>();
	}

	@Override
	public double updateCloudletsProcessing(double currentTime) {
		// insert in each vm packet received
		receivePackets();

		double smallerTime = super.updateCloudletsProcessing(currentTime);

		// send the packets to other hosts/VMs
		sendPackets();

		return smallerTime;
	}

	/**
	 * Receives packets and forward them to the corresponding VM.
	 */
	private void receivePackets() {
		for (NetworkPacket hs : pktReceived) {
			hs.pkt.recvTime = CloudSim.clock();

			// insert the packet in receivedlist of VM
			Vm vm = VmList.getById(getGuestList(), hs.pkt.receiverVmId);
			List<HostPacket> pktlist = ((NetworkCloudletSpaceSharedScheduler) vm.getCloudletScheduler()).pktrecv
					.computeIfAbsent(hs.pkt.senderVmId, k -> new ArrayList<>());

			pktlist.add(hs.pkt);

		}
		pktReceived.clear();
	}

	/**
	 * Sends packets checks whether a packet belongs to a local VM or to a 
         * VM hosted on other machine.
	 */
	private void sendPackets() {
		boolean flag = false;

		// Retrieve packets to be sent
		for (GuestEntity senderVm : super.getGuestList()) {
			Map<Integer, List<HostPacket>> pkttosend = ((NetworkCloudletSpaceSharedScheduler) senderVm
															.getCloudletScheduler()).pkttosend;
			for (Entry<Integer, List<HostPacket>> es : pkttosend.entrySet()) {
				List<HostPacket> pktlist = es.getValue();
				for (HostPacket hpkt : pktlist) {
					NetworkPacket npkt = new NetworkPacket(getId(), hpkt);
					GuestEntity targetVm = VmList.getById(this.getGuestList(), npkt.receiverVmId);
					if (targetVm != null) { // send locally to Vm, no network delay
							flag = true;
							sendPacketLocally(npkt, targetVm);
					} else { // send to edge switch, as target VM is hosted on another host
							sendPacketGlobally(npkt, pkttosend);
					}
				}
				pktlist.clear();
			}
		}

		if (flag) {
			for (GuestEntity vm : super.getGuestList()) {
				vm.updateCloudletsProcessing(CloudSim.clock(), getGuestScheduler().getAllocatedMipsForGuest(vm));
			}
		}
	}

	private void sendPacketLocally(NetworkPacket npkt, GuestEntity targetVm) {
		npkt.sendTime = npkt.recvTime;
		npkt.pkt.recvTime = CloudSim.clock();
		// insert the packet in receivedlist
		List<HostPacket> pktlist = ((NetworkCloudletSpaceSharedScheduler) targetVm.getCloudletScheduler()).pktrecv
				.computeIfAbsent(npkt.pkt.senderVmId, k -> new ArrayList<>());
		pktlist.add(npkt.pkt);
	}

	private void sendPacketGlobally(NetworkPacket npkt, Map<Integer, List<HostPacket>> pkttosend) {
		GuestEntity senderVm = VmList.getById(this.getGuestList(), npkt.senderVmId);
		int totalPkts = pkttosend.values().stream().mapToInt(List::size).sum();

		// Assumption: no overprovisioning of Vm's bandwidths
		double avband = (double) senderVm.getBw() / totalPkts;
		double delay = (1000 * npkt.pkt.data) / avband;

		NetworkTags.totaldatatransfer += npkt.pkt.data;

		// send to switch with delay
		CloudSim.send(getDatacenter().getId(), sw.getId(), delay, CloudSimTags.NETWORK_PKT_UP, npkt);
	}
}

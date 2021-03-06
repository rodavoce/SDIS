package protrocols;

import java.net.DatagramPacket;
import java.util.ArrayList;
import java.util.EnumMap;
import filesystem.Database;
import filesystem.ChunkData;
import peer.Peer;
import utils.Message;
import utils.Message.Field;
import utils.Utils;

public class BackupProtocol implements Runnable {
	private Peer peer;
	private DatagramPacket packet;

	public BackupProtocol(Peer peer, DatagramPacket packet) {
		this.packet = packet;
		this.peer = peer;
	}

	private void saveChunk(Message msg) {
		String storageId  = msg.getFileId();

		peer.getFs().saveChunk(storageId,msg.getChunkNo(),msg.getData());
	}

	private Message buildStoredMessage(Message originalMsg) {
		EnumMap<Field, String> messageHeader = new EnumMap<Field, String>(Field.class);

		messageHeader.put(Field.MESSAGE_TYPE, "STORED");
		messageHeader.put(Field.VERSION, peer.getProtocolVersion());
		messageHeader.put(Field.SENDER_ID, peer.getId());
		messageHeader.put(Field.FILE_ID, originalMsg.getFileId());
		messageHeader.put(Field.CHUNK_NO, Integer.toString(originalMsg.getChunkNo()));

		return new Message(messageHeader);
	}
	
	// checks if it's possible to clear space by deleting only files with higher than desired replication degree
	private boolean possibleToClearSpace(int sizeToClear) {
		ArrayList<ChunkData> chunksHigherReplication = peer.getDB().getChunksHigherReplication();
		int accumulated = 0;
		
		for (ChunkData chunk : chunksHigherReplication) {
			accumulated += chunk.getChunkSize();
			
			if (accumulated >= sizeToClear) {
				return true;
			}
		}
		
		return false;
	}
	
	private boolean clearSpace(int sizeToClear) {
		if (possibleToClearSpace(sizeToClear)) {
			Reclaim reclaim = new Reclaim(peer, peer.getDisk().getMaxSize());
			reclaim.removeChunks(sizeToClear);
			
			return true;
		}
		
		return false;
	}

	private boolean updateDB(Message msg) {

		String chunkKey = msg.getFileId()+msg.getChunkNo();

		Database db = peer.getDB();

		if(db.chunkOnDB(chunkKey)) {
			return false;
		} else {

			if(!peer.getDisk().reserveSpace(msg.getData().length)) {
				if (!clearSpace(msg.getData().length)) {
					System.out.println("Not enough space to store chunk.");
					return false;
				}
				System.out.println("Successfully cleared space to store chunk.");
			}

			db.saveChunkInfo(chunkKey, new ChunkData(chunkKey, 1, msg.getReplicationDeg(), msg.getData().length, msg.getFileId(), msg.getChunkNo()));
		}
		return true;
	}


	private boolean updateDBV2(Message msg) {
		String chunkKey = msg.getFileId()+msg.getChunkNo();

		Database db = peer.getDB();

		if(!peer.getDisk().reserveSpace(msg.getData().length)) {
			if (!clearSpace(msg.getData().length)) {
				System.out.println("Not enough space to store chunk.");
				return false;
			}
			System.out.println("Successfully cleared space to store chunk.");
			return false;
		}

		if(db.chunkOnDB(chunkKey)) {
			
			ChunkData data = db.getChunkInfo(chunkKey);
			
			if(data.getCurrentReplication() >= msg.getReplicationDeg()) {
				peer.getDisk().releaseSpace(msg.getData().length);
				db.removeChunk(chunkKey);
				return false;
			}


			data.setChunkSize(msg.getData().length);
			data.setCurrentReplication(data.getCurrentReplication() + 1);
			data.setMinReplication(msg.getReplicationDeg());
			db.saveChunkInfo(chunkKey, data);


		}
		else {
			db.saveChunkInfo(chunkKey, new ChunkData(chunkKey, 1, msg.getReplicationDeg(), msg.getData().length, msg.getFileId(), msg.getChunkNo()));
		}
		return true;
	}
	
	private void handlePacketV2(Message msg) {

		if (msg.getType().equals("PUTCHUNK")) {
				
			try {
				Thread.sleep(Utils.randomNumber(400, 800));
			} catch (InterruptedException e) {
				System.out.println("fail backup 1º sleep");
			}

			if(updateDBV2(msg)) {
				saveChunk(msg);
				Message response = buildStoredMessage(msg);
				System.out.println("OUT " + response.toString());
				
				try {
					Thread.sleep(Utils.randomNumber(0, 400));
				} catch (InterruptedException e) {
					System.out.println("fail backup 1º sleep");
				}
				
				peer.getControlChannel().sendMessage(response);

				System.out.println("Stored");

			}
			peer.saveDB(); //DB
		}

	}

	private void handlePacket(Message msg) {
		System.out.println("Received PUTCHUNK with size " + msg.getMsg().length + " and body size:" + msg.getData().length);
		System.out.println("ChunkNo:" + msg.getChunkNo());
		
		if (msg.getType().equals("PUTCHUNK")) {
			if (updateDB(msg)) {

				saveChunk(msg);
				Message response = buildStoredMessage(msg);
				System.out.println("OUT " + response.toString());
				try {
					Thread.sleep(Utils.randomNumber(0, 400));
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				peer.getControlChannel().sendMessage(response);
			}
			peer.saveDB();//DB
		}
	}



	@Override
	public void run() {
		Message msg = new Message(packet);
		
		// Don't store if chunk corresponds to a file sent by this peer
		if (peer.getDB().sentFileId(msg.getFileId())) {
			return;
		}
		
		if (peer.getProtocolVersion().equals("1.0")) {
			handlePacket(msg);
		}
		else if (peer.getProtocolVersion().equals("2.0")) {
			handlePacketV2(msg);
		}
	}
}

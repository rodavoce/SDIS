package protrocols;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.EnumMap;
import filesystem.FileData;
import filesystem.ChunkData;
import peer.Peer;
import utils.Message;
import utils.Message.Field;
import utils.Utils;

public class BackupInitiator implements Runnable {
	private Peer peer;
	private String filePath;
	private int replicationDegree;
	private int replication = 0;
	private ChunkData chunk;
	
	private static final int MAX_TRIES = 5;

	public BackupInitiator(Peer peer, String filePath, int replicationdegree) {
		this.peer = peer;
		this.filePath = filePath;
		this.replicationDegree = replicationdegree;
	}
	
	public BackupInitiator(Peer peer, ChunkData chunk) {
		this.peer = peer;
		this.chunk = chunk;
	}
	
	private Message buildPutChunkMessage(String fileId, Integer replicationDegree, Integer chunkNo, byte[] chunk) {
		EnumMap<Field, String> messageHeader = new EnumMap<Field, String>(Field.class);
		
		messageHeader.put(Field.MESSAGE_TYPE, "PUTCHUNK");
		messageHeader.put(Field.VERSION, peer.getProtocolVersion());
		messageHeader.put(Field.SENDER_ID, peer.getId());
		messageHeader.put(Field.FILE_ID, fileId);
		messageHeader.put(Field.REPLICATION_DEGREE, Integer.toString(replicationDegree));
		messageHeader.put(Field.CHUNK_NO, Integer.toString(chunkNo));
		
		return new Message(messageHeader, chunk);
	}
	
	private void sendFile() {
		if (peer.getFs().fileExist(filePath)) {
			try {
				File load = new File(filePath);
				FileData info = new FileData(load, replicationDegree);
				String fileid = info.getFileId();
				peer.getDB().saveStoredFile(filePath, info);
				ArrayList<byte[]> splitted = peer.getFs().splitFile(load);
				
				info.setChunkNo(splitted.size());
				
				for(int i = 0; i < splitted.size(); i++) {
					byte[] chunkData = splitted.get(i);
					Message putchunk = buildPutChunkMessage(fileid, replicationDegree, i, chunkData);
					String chunkKey = fileid + i;
					
					peer.getControlChannel().addBackupInitiator(chunkKey, this);
					sendPackage(putchunk);
					replication = 0;
					peer.getControlChannel().removeBackupInitiator(chunkKey);
					
					System.out.println(putchunk.toString());
				}
				
				peer.getDB().saveStoredFile(filePath, info);

			} catch (Exception e) {
				e.printStackTrace();
				System.out.println("BackupInitiator: unable to load file");
			}

		}
		else {
			System.out.println("Backup Initiator: file doesn't exist");
		}
	}
	
	void sendChunk() {
		byte[] storedChunk;
		
		try {
			storedChunk = peer.getFs().loadChunk(chunk.getFileId(), chunk.getChunkNo());
			Message putchunk = buildPutChunkMessage(chunk.getFileId(), replicationDegree, chunk.getChunkNo(), storedChunk);
			sendPackage(putchunk);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		peer.saveDB(); // DB
	}

	@Override
	public void run() {
		if (chunk != null) {
			// activate flag to listen to related PUTCHUNK messages
			peer.getDB().listenPutChunkFlag(chunk.getChunkKey());
			
			try {
				Thread.sleep(Utils.randomNumber(0, 400));
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			
			// if chunk has already been sent
			if (!peer.getDB().getPutChunkSent(chunk.getChunkKey())) {
				peer.getDB().removePutChunkFlag(chunk.getChunkKey());
				sendChunk();
			}
			
		} else {
			sendFile();
		}
	}



	private void sendPackage(Message putchunk) {
		int attempts = 0;
			
		while (attempts <= MAX_TRIES) {
			peer.getBackupChannel().sendMessage(putchunk);	
			try {
				Thread.sleep(1000*(attempts+1));
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		
			if (replication >= replicationDegree) {
				return;
			}
			
			attempts++;
		}
		
		
	}
	
	public void increaseReplicationDegree() {
		replication += 1;
		
	}
}


import express from 'express';
import { AccessToken } from 'livekit-server-sdk';
import dotenv from 'dotenv';

dotenv.config();

const app = express();
app.use(express.json());

app.post('/api/createToken', async (req, res) => {
  const { participantName = 'quickstart-username', roomName = 'quickstart-room' } = req.body;
  console.log("Creating token for " + participantName + " at " + roomName );
  
  try {
    const ttl = parseInt(process.env.LIVEKIT_TTL, 10); // Default to 10hours (36000 seconds)

    const at = new AccessToken(process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET, {
      identity: participantName,
      ttl: ttl,
    });

    at.addGrant({ roomJoin: true, room: roomName });

    const token = await at.toJwt();
    
    res.json({
      token,
      ttl: ttl,
      server_url: process.env.LIVEKIT_SERVER_URL
    });
  } catch (error) {
    console.error('Error generating token:', error);
    res.status(500).json({ error: 'Error generating token', details: error.message });
  }
});

const PORT = process.env.LIVEKIT_PORT || 3000;
app.listen(PORT, () => {
  console.log(`Server running on port ${PORT}`);
});

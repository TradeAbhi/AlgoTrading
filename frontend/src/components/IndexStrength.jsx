import { useState } from 'react';
import { indexStrengthApi } from '../api';

function IndexStrength() {
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [result, setResult] = useState(null);

  const handleAlert = async () => {
    setLoading(true);
    try {
      const response = await indexStrengthApi.alert();
      setResult(response);
      setMessage('Index strength alert sent to Telegram');
    } catch (error) {
      setMessage('Error sending alert: ' + error.message);
    }
    setLoading(false);
  };

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>📈 Index Strength Alert</h2>
      
      {message && (
        <div style={styles.message}>
          {message}
          <button onClick={() => setMessage('')} style={styles.closeBtn}>×</button>
        </div>
      )}

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Send Index Strength Alert</h3>
        <p style={styles.description}>
          Manually trigger the Index Strength alert. This analyzes the strength of Nifty 50, 
          Bank Nifty, and Fin Nifty indices and sends a Telegram alert with the analysis.
        </p>
        
        <button 
          onClick={handleAlert} 
          disabled={loading} 
          style={styles.primaryButton}
        >
          {loading ? 'Sending...' : '📊 Send Index Strength Alert'}
        </button>
      </div>

      {result && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>Alert Result</h3>
          <div style={styles.resultBox}>
            <div style={styles.resultItem}>
              <span style={styles.resultLabel}>Status:</span>
              <span style={styles.resultValue}>{result.status}</span>
            </div>
          </div>
        </div>
      )}

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Index Details</h3>
        <div style={styles.infoList}>
          <div style={styles.infoItem}>
            <strong>📊 Nifty 50:</strong> Benchmark index for Indian equity market
          </div>
          <div style={styles.infoItem}>
            <strong>🏦 Bank Nifty:</strong> Banking sector index
          </div>
          <div style={styles.infoItem}>
            <strong>💰 Fin Nifty:</strong> Financial services sector index
          </div>
          <div style={styles.infoItem}>
            <strong>📱 Alert:</strong> Sends Telegram message with index strength analysis
          </div>
          <div style={styles.infoItem}>
            <strong>⚡ Metrics:</strong> RSI, MACD, moving averages, volume analysis
          </div>
        </div>
      </div>
    </div>
  );
}

const styles = {
  container: {
    padding: '20px',
    background: '#f8f9fa',
    borderRadius: '8px'
  },
  title: {
    color: '#333',
    marginBottom: '20px'
  },
  message: {
    padding: '12px',
    background: '#d4edda',
    border: '1px solid #c3e6cb',
    borderRadius: '4px',
    marginBottom: '20px',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center'
  },
  closeBtn: {
    background: 'none',
    border: 'none',
    fontSize: '18px',
    cursor: 'pointer'
  },
  section: {
    background: 'white',
    padding: '20px',
    borderRadius: '8px',
    marginBottom: '20px'
  },
  sectionTitle: {
    color: '#666',
    marginBottom: '15px',
    fontSize: '18px'
  },
  description: {
    color: '#555',
    lineHeight: '1.6',
    marginBottom: '20px'
  },
  primaryButton: {
    padding: '12px 24px',
    background: '#667eea',
    color: 'white',
    border: 'none',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500'
  },
  resultBox: {
    background: '#f8f9fa',
    padding: '15px',
    borderRadius: '4px'
  },
  resultItem: {
    display: 'flex',
    gap: '10px'
  },
  resultLabel: {
    fontWeight: '500',
    color: '#666'
  },
  resultValue: {
    fontWeight: 'bold',
    color: '#333'
  },
  infoList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '10px'
  },
  infoItem: {
    padding: '10px',
    background: '#f8f9fa',
    borderRadius: '4px',
    fontSize: '14px',
    color: '#555'
  }
};

export default IndexStrength;

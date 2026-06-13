import { useState } from 'react';
import { sentimentApi } from '../api';

function MarketSentiment() {
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [lastTrigger, setLastTrigger] = useState('');

  const triggerAlert = async (alertType) => {
    setLoading(true);
    try {
      let response;
      switch(alertType) {
        case 'morning':
          response = await sentimentApi.morning();
          break;
        case 'midday':
          response = await sentimentApi.midday();
          break;
        case 'preclose':
          response = await sentimentApi.preclose();
          break;
        case 'eod':
          response = await sentimentApi.eod();
          break;
        case 'pcr':
          response = await sentimentApi.pcrAlert();
          break;
        case 'vix':
          response = await sentimentApi.vixAlert();
          break;
        case 'breadth':
          response = await sentimentApi.breadthAlert();
          break;
      }
      setLastTrigger(alertType);
      setMessage(`${alertType.toUpperCase()} alert triggered: ${response}`);
    } catch (error) {
      setMessage('Error triggering alert: ' + error.message);
    }
    setLoading(false);
  };

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>📊 Market Sentiment Alerts</h2>
      
      {message && (
        <div style={styles.message}>
          {message}
          <button onClick={() => setMessage('')} style={styles.closeBtn}>×</button>
        </div>
      )}

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Time-Based Snapshots</h3>
        <div style={styles.buttonGrid}>
          <button 
            onClick={() => triggerAlert('morning')} 
            disabled={loading} 
            style={styles.primaryButton}
          >
            ☀️ Morning Snapshot
          </button>
          <button 
            onClick={() => triggerAlert('midday')} 
            disabled={loading} 
            style={styles.primaryButton}
          >
            🌤️ Midday Snapshot
          </button>
          <button 
            onClick={() => triggerAlert('preclose')} 
            disabled={loading} 
            style={styles.primaryButton}
          >
            🌅 Pre-Close Snapshot
          </button>
          <button 
            onClick={() => triggerAlert('eod')} 
            disabled={loading} 
            style={styles.primaryButton}
          >
            🌙 EOD Summary
          </button>
        </div>
      </div>

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Indicator Alerts</h3>
        <div style={styles.buttonGrid}>
          <button 
            onClick={() => triggerAlert('pcr')} 
            disabled={loading} 
            style={styles.secondaryButton}
          >
            📈 PCR Extreme Alert
          </button>
          <button 
            onClick={() => triggerAlert('vix')} 
            disabled={loading} 
            style={styles.secondaryButton}
          >
            ⚡ VIX Spike Alert
          </button>
          <button 
            onClick={() => triggerAlert('breadth')} 
            disabled={loading} 
            style={styles.secondaryButton}
          >
            📊 Breadth Extreme Alert
          </button>
        </div>
      </div>

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Alert Descriptions</h3>
        <div style={styles.infoList}>
          <div style={styles.infoItem}>
            <strong>☀️ Morning Snapshot:</strong> Market sentiment at market open (9:15 AM)
          </div>
          <div style={styles.infoItem}>
            <strong>🌤️ Midday Snapshot:</strong> Market sentiment at midday (12:30 PM)
          </div>
          <div style={styles.infoItem}>
            <strong>🌅 Pre-Close Snapshot:</strong> Market sentiment before close (3:00 PM)
          </div>
          <div style={styles.infoItem}>
            <strong>🌙 EOD Summary:</strong> End-of-day sentiment summary
          </div>
          <div style={styles.infoItem}>
            <strong>📈 PCR Extreme Alert:</strong> Put-Call Ratio extreme levels
          </div>
          <div style={styles.infoItem}>
            <strong>⚡ VIX Spike Alert:</strong> India VIX spike detection
          </div>
          <div style={styles.infoItem}>
            <strong>📊 Breadth Extreme Alert:</strong> Advance-Decline breadth extremes
          </div>
        </div>
      </div>

      {lastTrigger && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>Last Triggered</h3>
          <div style={styles.statusBox}>
            <span style={styles.statusLabel}>Alert Type:</span>
            <span style={styles.statusValue}>{lastTrigger.toUpperCase()}</span>
          </div>
        </div>
      )}
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
  buttonGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
    gap: '10px'
  },
  primaryButton: {
    padding: '12px 20px',
    background: '#667eea',
    color: 'white',
    border: 'none',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500'
  },
  secondaryButton: {
    padding: '12px 20px',
    background: '#6c757d',
    color: 'white',
    border: 'none',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500'
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
  },
  statusBox: {
    display: 'flex',
    gap: '20px',
    padding: '15px',
    background: '#e7f3ff',
    borderRadius: '4px'
  },
  statusLabel: {
    fontWeight: '500',
    color: '#666'
  },
  statusValue: {
    fontWeight: 'bold',
    color: '#333'
  }
};

export default MarketSentiment;

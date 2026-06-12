import { useState } from 'react';
import { deltaApi } from '../api';

function Backtest() {
  const [activeTab, setActiveTab] = useState('delta');
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [result, setResult] = useState(null);

  // Delta Backtest Form
  const [deltaParams, setDeltaParams] = useState({
    symbol: 'BTCUSD',
    from: '',
    to: '',
    slMarginPct: 0.15,
    riskRewardRatio: 3.0,
    partialExitRR: 2.0,
    partialExitQtyPct: 50.0
  });

  // Volume Backtest Form
  const [volumeParams, setVolumeParams] = useState({
    symbol: 'BTCUSD',
    from: '',
    to: '',
    spikeMultiplier: 2.0,
    climaxMultiplier: 3.0,
    riskPercent: 1.0,
    breakoutRR: 3.0,
    absorptionRR: 2.0,
    climaxRR: 2.0,
    slMarginPct: 0.15,
    srLookback: 50,
    srPivotStrength: 3,
    srProximityPct: 0.5,
    srFilterEnabled: true
  });

  const handleDeltaBacktest = async () => {
    setLoading(true);
    try {
      const response = await deltaApi.runBacktest(deltaParams);
      setResult(response);
      setMessage('Delta backtest completed');
    } catch (error) {
      setMessage('Error running delta backtest: ' + error.message);
    }
    setLoading(false);
  };

  const handleVolumeBacktest = async () => {
    setLoading(true);
    try {
      const response = await deltaApi.runVolumeBacktest(volumeParams);
      setResult(response);
      setMessage('Volume backtest completed');
    } catch (error) {
      setMessage('Error running volume backtest: ' + error.message);
    }
    setLoading(false);
  };

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>📈 Backtest Engine</h2>
      
      {message && (
        <div style={styles.message}>
          {message}
          <button onClick={() => setMessage('')} style={styles.closeBtn}>×</button>
        </div>
      )}

      {/* Tabs */}
      <div style={styles.tabs}>
        <button
          onClick={() => setActiveTab('delta')}
          style={{
            ...styles.tab,
            ...(activeTab === 'delta' ? styles.activeTab : {})
          }}
        >
          Delta Backtest
        </button>
        <button
          onClick={() => setActiveTab('volume')}
          style={{
            ...styles.tab,
            ...(activeTab === 'volume' ? styles.activeTab : {})
          }}
        >
          Volume Backtest
        </button>
      </div>

      {/* Delta Backtest Form */}
      {activeTab === 'delta' && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>Delta Backtest Parameters</h3>
          <div style={styles.formGrid}>
            <div style={styles.formGroup}>
              <label style={styles.label}>Symbol</label>
              <input
                type="text"
                value={deltaParams.symbol}
                onChange={(e) => setDeltaParams({...deltaParams, symbol: e.target.value})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>From Date</label>
              <input
                type="date"
                value={deltaParams.from}
                onChange={(e) => setDeltaParams({...deltaParams, from: e.target.value})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>To Date</label>
              <input
                type="date"
                value={deltaParams.to}
                onChange={(e) => setDeltaParams({...deltaParams, to: e.target.value})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>SL Margin %</label>
              <input
                type="number"
                step="0.01"
                value={deltaParams.slMarginPct}
                onChange={(e) => setDeltaParams({...deltaParams, slMarginPct: parseFloat(e.target.value)})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>Risk:Reward Ratio</label>
              <input
                type="number"
                step="0.1"
                value={deltaParams.riskRewardRatio}
                onChange={(e) => setDeltaParams({...deltaParams, riskRewardRatio: parseFloat(e.target.value)})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>Partial Exit RR</label>
              <input
                type="number"
                step="0.1"
                value={deltaParams.partialExitRR}
                onChange={(e) => setDeltaParams({...deltaParams, partialExitRR: parseFloat(e.target.value)})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>Partial Exit Qty %</label>
              <input
                type="number"
                step="0.1"
                value={deltaParams.partialExitQtyPct}
                onChange={(e) => setDeltaParams({...deltaParams, partialExitQtyPct: parseFloat(e.target.value)})}
                style={styles.input}
              />
            </div>
          </div>
          <button 
            onClick={handleDeltaBacktest} 
            disabled={loading}
            style={styles.button}
          >
            {loading ? 'Running...' : 'Run Delta Backtest'}
          </button>
        </div>
      )}

      {/* Volume Backtest Form */}
      {activeTab === 'volume' && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>Volume Backtest Parameters</h3>
          <div style={styles.formGrid}>
            <div style={styles.formGroup}>
              <label style={styles.label}>Symbol</label>
              <input
                type="text"
                value={volumeParams.symbol}
                onChange={(e) => setVolumeParams({...volumeParams, symbol: e.target.value})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>From Date</label>
              <input
                type="date"
                value={volumeParams.from}
                onChange={(e) => setVolumeParams({...volumeParams, from: e.target.value})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>To Date</label>
              <input
                type="date"
                value={volumeParams.to}
                onChange={(e) => setVolumeParams({...volumeParams, to: e.target.value})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>Spike Multiplier</label>
              <input
                type="number"
                step="0.1"
                value={volumeParams.spikeMultiplier}
                onChange={(e) => setVolumeParams({...volumeParams, spikeMultiplier: parseFloat(e.target.value)})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>Climax Multiplier</label>
              <input
                type="number"
                step="0.1"
                value={volumeParams.climaxMultiplier}
                onChange={(e) => setVolumeParams({...volumeParams, climaxMultiplier: parseFloat(e.target.value)})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>Risk %</label>
              <input
                type="number"
                step="0.1"
                value={volumeParams.riskPercent}
                onChange={(e) => setVolumeParams({...volumeParams, riskPercent: parseFloat(e.target.value)})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>Breakout RR</label>
              <input
                type="number"
                step="0.1"
                value={volumeParams.breakoutRR}
                onChange={(e) => setVolumeParams({...volumeParams, breakoutRR: parseFloat(e.target.value)})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>Absorption RR</label>
              <input
                type="number"
                step="0.1"
                value={volumeParams.absorptionRR}
                onChange={(e) => setVolumeParams({...volumeParams, absorptionRR: parseFloat(e.target.value)})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>Climax RR</label>
              <input
                type="number"
                step="0.1"
                value={volumeParams.climaxRR}
                onChange={(e) => setVolumeParams({...volumeParams, climaxRR: parseFloat(e.target.value)})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>SL Margin %</label>
              <input
                type="number"
                step="0.01"
                value={volumeParams.slMarginPct}
                onChange={(e) => setVolumeParams({...volumeParams, slMarginPct: parseFloat(e.target.value)})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>SR Lookback</label>
              <input
                type="number"
                value={volumeParams.srLookback}
                onChange={(e) => setVolumeParams({...volumeParams, srLookback: parseInt(e.target.value)})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>SR Pivot Strength</label>
              <input
                type="number"
                value={volumeParams.srPivotStrength}
                onChange={(e) => setVolumeParams({...volumeParams, srPivotStrength: parseInt(e.target.value)})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>SR Proximity %</label>
              <input
                type="number"
                step="0.1"
                value={volumeParams.srProximityPct}
                onChange={(e) => setVolumeParams({...volumeParams, srProximityPct: parseFloat(e.target.value)})}
                style={styles.input}
              />
            </div>
            <div style={styles.formGroup}>
              <label style={styles.label}>SR Filter Enabled</label>
              <select
                value={volumeParams.srFilterEnabled}
                onChange={(e) => setVolumeParams({...volumeParams, srFilterEnabled: e.target.value === 'true'})}
                style={styles.input}
              >
                <option value="true">Yes</option>
                <option value="false">No</option>
              </select>
            </div>
          </div>
          <button 
            onClick={handleVolumeBacktest} 
            disabled={loading}
            style={styles.button}
          >
            {loading ? 'Running...' : 'Run Volume Backtest'}
          </button>
        </div>
      )}

      {/* Results Section */}
      {result && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>Backtest Results</h3>
          <pre style={styles.result}>
            {JSON.stringify(result, null, 2)}
          </pre>
        </div>
      )}
    </div>
  );
}

const styles = {
  container: {
    padding: '20px',
    background: '#f8f9fa',
    borderRadius: '8px',
    marginBottom: '20px'
  },
  title: {
    color: '#333',
    marginBottom: '20px',
    fontSize: '22px'
  },
  message: {
    background: '#e3f2fd',
    border: '1px solid #2196f3',
    borderRadius: '8px',
    padding: '12px',
    marginBottom: '20px',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    color: '#1976d2'
  },
  closeBtn: {
    background: 'none',
    border: 'none',
    fontSize: '20px',
    cursor: 'pointer',
    color: '#1976d2'
  },
  tabs: {
    display: 'flex',
    gap: '5px',
    marginBottom: '20px',
    flexWrap: 'wrap'
  },
  tab: {
    padding: '10px 20px',
    background: '#e0e0e0',
    border: 'none',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500'
  },
  activeTab: {
    background: '#ff5722',
    color: 'white'
  },
  section: {
    marginBottom: '20px',
    padding: '15px',
    background: 'white',
    borderRadius: '8px',
    border: '1px solid #e0e0e0'
  },
  sectionTitle: {
    color: '#333',
    marginBottom: '15px',
    fontSize: '16px'
  },
  formGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
    gap: '15px',
    marginBottom: '15px'
  },
  formGroup: {
    display: 'flex',
    flexDirection: 'column'
  },
  label: {
    marginBottom: '5px',
    fontSize: '13px',
    fontWeight: '500',
    color: '#555'
  },
  input: {
    padding: '8px',
    border: '1px solid #ddd',
    borderRadius: '6px',
    fontSize: '14px'
  },
  button: {
    padding: '12px 24px',
    background: '#ff5722',
    color: 'white',
    border: 'none',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '600',
    transition: 'background 0.2s'
  },
  result: {
    background: '#f8f9fa',
    padding: '15px',
    borderRadius: '6px',
    overflow: 'auto',
    fontSize: '12px',
    maxHeight: '400px'
  }
};

export default Backtest;
